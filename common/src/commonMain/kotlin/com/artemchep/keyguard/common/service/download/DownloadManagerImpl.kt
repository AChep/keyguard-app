package com.artemchep.keyguard.common.service.download

import arrow.core.right
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.keepass.isKeePassAttachmentUrl
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.getHttpCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerImpl(
    private val windowCoroutineScope: WindowCoroutineScope,
    private val downloadRepository: DownloadRepository,
    private val sourceLoader: DownloadAttachmentSourceLoader,
    private val downloadFileStore: DownloadFileStore,
    private val downloadBackgroundScheduler: DownloadBackgroundScheduler,
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
) : DownloadManager {
    private val downloadInfoRepositoryController = DownloadInfoRepositoryController(
        downloadRepository = downloadRepository,
        base64Service = base64Service,
        cryptoGenerator = cryptoGenerator,
    )

    private val progressById = MutableStateFlow(emptyMap<String, DownloadProgress>())

    private val progressByTag =
        MutableStateFlow(emptyMap<DownloadInfoEntity.AttachmentDownloadTag, DownloadProgress>())

    // Protect only the lock registry. Writer cleanup and file I/O hold the attachment's
    // lock, so operations for other attachments can proceed independently.
    private val operationsMutex = Mutex()

    private val operationsByTag =
        mutableMapOf<DownloadInfoEntity.AttachmentDownloadTag, AttachmentOperation>()

    private class AttachmentOperation {
        val mutex = Mutex()

        // Includes waiters: removing an entry while someone waits would allow a
        // second mutex for the same attachment to be created.
        var users = 0
    }

    private val activeScopesMutex = Mutex()

    private val activeScopesByDownloadId = mutableMapOf<String, CoroutineScope>()

    override fun statusByDownloadId2(downloadId: String): Flow<DownloadProgress> = progressById
        .map { state -> state[downloadId] }
        .distinctUntilChanged()
        .flatMapLatest { live ->
            live?.let(::flowOf)
                ?: downloadRepository
                    .getByIdFlow(downloadId)
                    .map { info -> info?.toStoredProgress() ?: DownloadProgress.None }
        }

    override fun statusByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): Flow<DownloadProgress> = progressByTag
        .map { state -> state[tag] }
        .distinctUntilChanged()
        .flatMapLatest { live ->
            live?.let(::flowOf)
                ?: downloadRepository
                    .getByTagFlow(tag)
                    .map { info -> info?.toStoredProgress() ?: DownloadProgress.None }
        }

    override suspend fun queue(
        downloadInfo: DownloadInfoEntity,
    ): DownloadManager.QueueResult = queue(
        DownloadQueueRequest(
            tag = downloadInfo.downloadTag(),
            source = if (downloadInfo.url.isKeePassAttachmentUrl()) {
                DownloadQueueRequest.Source.KeePass(
                    url = downloadInfo.url,
                    expectedSize = null,
                )
            } else {
                DownloadQueueRequest.Source.Url(
                    url = downloadInfo.url,
                    urlIsOneTime = downloadInfo.urlIsOneTime,
                )
            },
            name = downloadInfo.name,
            key = downloadInfo.encryptionKeyBase64?.let(base64Service::decode),
            attempt = downloadInfo.error?.attempt ?: 0,
        ),
    )

    override suspend fun queue(
        request: DownloadQueueRequest,
    ): DownloadManager.QueueResult = withAttachmentLock(request.tag) {
        downloadRepository.getByTag(request.tag).bind()?.let { info ->
            stopActiveDownloadScope(info.id)
        }

        val source = request.source
        val info = downloadInfoRepositoryController.getOrPutDownloadFileEntity(
            url = source.url,
            urlIsOneTime = source.urlIsOneTime,
            name = request.name,
            tag = request.tag,
            encryptionKey = request.key,
            error = null,
        )
        val flow = if (downloadFileStore.exists(info)) {
            existingFileFlow(info)
        } else {
            sourceLoader.fileLoader(
                request = request.toAttachmentRequestData(),
                writer = downloadFileStore.writer(info),
            )
        }

        val downloadScope = windowCoroutineScope + SupervisorJob()
        activeScopesMutex.withLock {
            activeScopesByDownloadId[info.id] = downloadScope
        }
        val sharedFlow = trackProgress(
            attempt = request.attempt,
            info = info,
            tag = request.tag,
            flow = flow,
            scope = downloadScope,
        ).shareIn(
            scope = downloadScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )
        val queueFlow = sharedFlow
            .transformWhile { progress ->
                emit(progress)
                progress is DownloadProgress.Loading
            }

        if (request.scheduleBackground) {
            downloadBackgroundScheduler.enqueue(info.id)
        }

        DownloadManager.QueueResult(
            info = info,
            flow = queueFlow,
        )
    }

    override suspend fun removeByDownloadId(downloadId: String) {
        val info = downloadRepository.getById(downloadId).bind() ?: return
        withAttachmentLock(info.downloadTag()) {
            // The row may have been removed and a new ID queued while we waited.
            val current = downloadRepository.getById(downloadId).bind()
                ?: return@withAttachmentLock
            removeDownload(current)
        }
    }

    override suspend fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ) = withAttachmentLock(tag) {
        val info = downloadRepository.getByTag(tag).bind() ?: return@withAttachmentLock
        removeDownload(info)
    }

    // The caller holds the attachment lock. Completion must remain independent of
    // that lock, and joining must stay outside the registry/repository locks.
    private suspend fun removeDownload(info: DownloadInfoEntity) {
        stopActiveDownloadScope(info.id)
        downloadFileStore.delete(info)
        downloadInfoRepositoryController.removeByDownloadId(info.id)
    }

    private suspend fun <T> withAttachmentLock(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
        block: suspend () -> T,
    ): T {
        val operation = operationsMutex.withLock {
            operationsByTag.getOrPut(tag, ::AttachmentOperation).also { it.users++ }
        }
        try {
            return operation.mutex.withLock { block() }
        } finally {
            // A cancelled waiter or holder must release its registry reference too.
            withContext(NonCancellable) {
                operationsMutex.withLock {
                    if (--operation.users == 0) {
                        operationsByTag.remove(tag)
                    }
                }
            }
        }
    }

    private fun existingFileFlow(
        info: DownloadInfoEntity,
    ): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Loading())
        emit(DownloadProgress.Complete(downloadFileStore.uri(info).right()))
    }

    private fun trackProgress(
        attempt: Int,
        info: DownloadInfoEntity,
        tag: DownloadInfoEntity.AttachmentDownloadTag,
        flow: Flow<DownloadProgress>,
        scope: CoroutineScope,
    ): Flow<DownloadProgress> = flow
        .onEach { progress ->
            progressById.update { state -> state + (info.id to progress) }
            progressByTag.update { state -> state + (tag to progress) }
            if (progress is DownloadProgress.Complete) {
                progress.result.fold(
                    ifLeft = { e ->
                        downloadInfoRepositoryController.replaceDownloadFileEntity(
                            id = info.id,
                            error = DownloadInfoEntity.Error(
                                code = e.getHttpCode(),
                                message = e.message,
                                attempt = if (
                                    e is DownloadAttachmentSessionUnavailableException
                                ) {
                                    // Waiting for the vault must not consume a transfer attempt.
                                    attempt
                                } else {
                                    attempt + 1
                                },
                            ),
                        )
                    },
                    ifRight = {
                        downloadInfoRepositoryController.replaceDownloadFileEntity(
                            id = info.id,
                            error = null,
                        )
                    },
                )
            }
        }
        .onCompletion {
            // A cancelled writer must still clear its progress and registration.
            withContext(NonCancellable) {
                clearActiveDownloadScope(info.id, tag, scope)
                scope.cancel()
            }
        }

    private suspend fun stopActiveDownloadScope(
        downloadId: String,
    ) {
        val scope = activeScopesMutex.withLock {
            activeScopesByDownloadId[downloadId]
        }
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
    }

    private suspend fun clearActiveDownloadScope(
        downloadId: String,
        tag: DownloadInfoEntity.AttachmentDownloadTag,
        scope: CoroutineScope,
    ): Unit = activeScopesMutex.withLock {
        if (activeScopesByDownloadId[downloadId] !== scope) {
            return@withLock
        }
        // Clear progress before another scope can register under the same ID.
        progressById.update { state -> state - downloadId }
        progressByTag.update { state -> state - tag }
        activeScopesByDownloadId.remove(downloadId)
    }

    private suspend fun DownloadInfoEntity.toStoredProgress(): DownloadProgress =
        toStoredDownloadProgress(
            uri = downloadFileStore.uri(this),
            fileExists = downloadFileStore.exists(this),
        )
}

private fun DownloadQueueRequest.toAttachmentRequestData() = DownloadAttachmentRequestData(
    localCipherId = tag.localCipherId,
    remoteCipherId = tag.remoteCipherId,
    attachmentId = tag.attachmentId,
    source = when (val source = source) {
        is DownloadQueueRequest.Source.Direct ->
            DownloadAttachmentRequestData.DirectSource(source.data)

        is DownloadQueueRequest.Source.Url ->
            DownloadAttachmentRequestData.UrlSource(
                url = source.url,
                urlIsOneTime = source.urlIsOneTime,
            )

        is DownloadQueueRequest.Source.KeePass ->
            DownloadAttachmentRequestData.KeePassSource(
                hashRef = source.url,
                expectedSize = source.expectedSize,
            )
    },
    name = name,
    encryptionKey = key,
)
