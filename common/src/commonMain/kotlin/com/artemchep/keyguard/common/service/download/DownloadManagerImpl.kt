package com.artemchep.keyguard.common.service.download

import arrow.core.right
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.getHttpCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.kodein.di.DirectDI
import org.kodein.di.instance

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerImpl(
    private val windowCoroutineScope: WindowCoroutineScope,
    private val downloadRepository: DownloadRepository,
    private val downloadTask: DownloadTask,
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

    private val activeScopesMutex = Mutex()

    private val activeScopesByDownloadId = mutableMapOf<String, CoroutineScope>()

    constructor(
        directDI: DirectDI,
    ) : this(
        windowCoroutineScope = directDI.instance(),
        downloadRepository = directDI.instance(),
        downloadTask = directDI.instance(),
        downloadFileStore = directDI.instance(),
        downloadBackgroundScheduler = directDI.instance(),
        base64Service = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

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
            source = DownloadQueueRequest.Source.Url(
                url = downloadInfo.url,
                urlIsOneTime = downloadInfo.urlIsOneTime,
            ),
            name = downloadInfo.name,
            key = downloadInfo.encryptionKeyBase64?.let(base64Service::decode),
            attempt = downloadInfo.error?.attempt ?: 0,
        ),
    )

    override suspend fun queue(
        request: DownloadQueueRequest,
    ): DownloadManager.QueueResult {
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
            when (source) {
                is DownloadQueueRequest.Source.Direct -> downloadTask.fileLoader(
                    data = source.data,
                    key = request.key,
                    writer = downloadFileStore.writer(info),
                )

                is DownloadQueueRequest.Source.Url -> downloadTask.fileLoader(
                    url = source.url,
                    key = request.key,
                    writer = downloadFileStore.writer(info),
                )
            }
        }

        val downloadScope = windowCoroutineScope + SupervisorJob()
        replaceActiveDownloadScope(info.id, downloadScope)
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

        return DownloadManager.QueueResult(
            info = info,
            flow = queueFlow,
        )
    }

    override suspend fun removeByDownloadId(downloadId: String) {
        cancelActiveDownloadScope(downloadId)
        downloadInfoRepositoryController.removeByDownloadId(downloadId) { info ->
            downloadFileStore.delete(info)
        }
    }

    override suspend fun removeByTag(tag: DownloadInfoEntity.AttachmentDownloadTag) {
        downloadInfoRepositoryController.removeByTag(tag) { info ->
            cancelActiveDownloadScope(info.id)
            downloadFileStore.delete(info)
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
                                attempt = attempt + 1,
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
            val cleared = clearActiveDownloadScope(info.id, scope)
            if (cleared) {
                progressById.update { state -> state - info.id }
                progressByTag.update { state -> state - tag }
            }
            scope.cancel()
        }

    private suspend fun replaceActiveDownloadScope(
        downloadId: String,
        scope: CoroutineScope,
    ) = activeScopesMutex.withLock {
        activeScopesByDownloadId
            .put(downloadId, scope)
            ?.cancel()
    }

    private suspend fun cancelActiveDownloadScope(
        downloadId: String,
    ) = activeScopesMutex.withLock {
        activeScopesByDownloadId
            .remove(downloadId)
            ?.cancel()
    }

    private suspend fun clearActiveDownloadScope(
        downloadId: String,
        scope: CoroutineScope,
    ): Boolean = activeScopesMutex.withLock {
        if (activeScopesByDownloadId[downloadId] !== scope) {
            return@withLock false
        }
        activeScopesByDownloadId.remove(downloadId)
        true
    }

    private suspend fun DownloadInfoEntity.toStoredProgress(): DownloadProgress =
        toStoredDownloadProgress(
            uri = downloadFileStore.uri(this),
            fileExists = downloadFileStore.exists(this),
        )
}
