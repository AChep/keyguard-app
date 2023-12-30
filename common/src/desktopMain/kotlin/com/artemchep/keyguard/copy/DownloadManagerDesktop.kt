package com.artemchep.keyguard.copy

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.CodeException
import com.artemchep.keyguard.common.util.getHttpCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

class DownloadManagerDesktop(
    private val windowCoroutineScope: WindowCoroutineScope,
    private val downloadClient: DownloadClientDesktop,
    private val downloadRepository: DownloadRepository,
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
    private val dataDirectory: DataDirectory,
) : DownloadManager {
    companion object {
        // Since the extension of the files is unknown it's safe
        // to say that they are just binaries.
        private const val CACHE_FILE_EXT = ".bin"

        fun getFile(
            dir: File,
            downloadId: String,
        ) = dir.resolve("$downloadId$CACHE_FILE_EXT")
    }

    private val mutex = Mutex()

    private val map = mutableMapOf<String, Job>()

    constructor(
        directDI: DirectDI,
    ) : this(
        windowCoroutineScope = directDI.instance(),
        downloadClient = directDI.instance(),
        downloadRepository = directDI.instance(),
        base64Service = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        dataDirectory = directDI.instance(),
    )

//    fun statusByAttachmentId(
//        accountId: String,
//        cipherId: String,
//        attachmentId: String,
//    ) = AttachmentDownloadTag(
//        accountId = accountId,
//        remoteCipherId = cipherId,
//        attachmentId = attachmentId,
//    ).serialize().let(::statusByTag)

    override fun statusByDownloadId2(downloadId: String): Flow<DownloadProgress> = downloadClient
        .fileStatusByDownloadId(downloadId)

    override fun statusByTag(tag: DownloadInfoEntity2.AttachmentDownloadTag): Flow<DownloadProgress> =
        downloadClient
            .fileStatusByTag(tag)
            .flatMapLatest { event ->
                when (event) {
                    is DownloadProgress.None -> {
                        downloadRepository
                            .get()
                            .map {
                                it
                                    .asSequence()
                                    .map {
                                        DownloadInfoEntity2.AttachmentDownloadTag(
                                            localCipherId = it.localCipherId,
                                            remoteCipherId = it.remoteCipherId,
                                            attachmentId = it.attachmentId,
                                        )
                                    }
                                    .toSet()
                            }
                            .map { tags -> tag.takeIf { it in tags } }
                            .map { tagOrNull ->
                                tagOrNull?.let { peekDownloadInfoByTag(it) }
                            }
                            .distinctUntilChanged()
                            .map { downloadInfo ->
                                downloadInfo
                                    ?: return@map DownloadProgress.None
                                val file = getFilePath(
                                    downloadId = downloadInfo.id,
                                    downloadName = downloadInfo.name,
                                )

                                when {
                                    file.exists() -> {
                                        val result = file.right()
                                        DownloadProgress.Complete(result)
                                    }

                                    downloadInfo.error != null -> {
                                        // TODO: Fix meee!!
                                        val exception = CodeException(
                                            code = downloadInfo.error!!.code,
                                            description = downloadInfo.error!!.message,
                                        )
                                        val result = exception.left()
                                        DownloadProgress.Complete(result)
                                    }

                                    else -> {
                                        DownloadProgress.Loading()
                                    }
                                }
                            }
                    }

                    else -> flowOf(event)
                }
            }

    @OptIn(FlowPreview::class)
    override suspend fun queue(
        downloadInfo: DownloadInfoEntity2,
    ): DownloadManager.QueueResult = queue(
        url = downloadInfo.url,
        urlIsOneTime = downloadInfo.urlIsOneTime,
        name = downloadInfo.name,
        tag = DownloadInfoEntity2.AttachmentDownloadTag(
            localCipherId = downloadInfo.localCipherId,
            remoteCipherId = downloadInfo.remoteCipherId,
            attachmentId = downloadInfo.attachmentId,
        ),
        attempt = downloadInfo.error?.attempt ?: 0,
        key = downloadInfo.encryptionKeyBase64?.let { base64Service.decode(it) },
    )

    @OptIn(FlowPreview::class)
    override suspend fun queue(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
        url: String,
        urlIsOneTime: Boolean,
        name: String,
        key: ByteArray?,
        attempt: Int,
        worker: Boolean,
    ): DownloadManager.QueueResult = kotlin.run {
        println("Queue??")
        val downloadInfo = getOrPutDownloadFileEntity(
            url = url,
            urlIsOneTime = urlIsOneTime,
            name = name,
            tag = tag,
            encryptionKey = key,
            error = null, // clears error field if existed
        )
        println("File??")
        val file = getFilePath(
            downloadInfo.id,
            downloadInfo.name,
        )
        println("File $file")

        val downloadCancelFlow = downloadRepository
            .getByIdFlow(id = downloadInfo.id)
            .mapNotNull { model ->
                Unit.takeIf { model == null }
            }
        val downloadFlow = downloadClient.fileLoader(
            downloadId = downloadInfo.id,
            url = url,
            tag = tag,
            file = file,
            fileKey = key,
            cancelFlow = downloadCancelFlow,
        )

        // Launch downloading job on
        // the app scope.
        listenToError(
            scope = windowCoroutineScope,
            attempt = attempt,
            downloadId = downloadInfo.id,
            downloadFlow = downloadFlow,
        )

        DownloadManager.QueueResult(
            info = downloadInfo,
            flow = downloadFlow,
        )
    }

    private suspend fun getFilePath(
        downloadId: String,
        downloadName: String,
    ) = dataDirectory
        .downloads()
        .bind()
        .let(::File)
        .resolve(downloadName)

    private fun listenToError(
        scope: CoroutineScope,
        attempt: Int,
        downloadId: String,
        downloadFlow: Flow<DownloadProgress>,
    ) {
        synchronized(map) {
            // Find out if current download job exists and still
            // valid. Create a new one otherwise.
            val existingJob = map[downloadId]
            if (
                existingJob != null &&
                !existingJob.isCancelled &&
                !existingJob.isCompleted
            ) {
                return@synchronized
            }

            val job = scope.launch {
                val downloadResult = downloadFlow
                    .onEach { downloadStatus ->
                        // Log.e("download", "progress $downloadStatus")
                    }
                    .last()
                if (downloadResult is DownloadProgress.Complete) {
                    downloadResult.result.fold(
                        ifLeft = { e ->
                            val error = DownloadInfoEntity2.Error(
                                code = e.getHttpCode(),
                                message = e.message,
                                attempt = attempt + 1,
                            )
                            replaceDownloadFileEntity(
                                 id = downloadId,
                                 error = error,
                             )
                            // Log.e("download", "im sorry, mate")
                        },
                        ifRight = {},
                    )
                }
            }
            job.invokeOnCompletion {
                synchronized(map) {
                    val latestJob = map[downloadId]
                    if (latestJob === job) map.remove(downloadId)
                }
            }
            map[downloadId] = job
        }
    }

    override suspend fun removeByDownloadId(
        downloadId: String,
    ) = removeDownloadInfoByDownloadId(id = downloadId)

    override suspend fun removeByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ) = removeDownloadInfoByTag(tag = tag)

    //
    // Download Info Repository
    //

    private suspend fun removeDownloadInfoByDownloadId(
        id: String,
    ) = mutex
        .withLock {
            downloadRepository.removeById(id = id)
                .bind()
        }

    private suspend fun removeDownloadInfoByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ) = mutex
        .withLock {
            downloadRepository.removeByTag(tag = tag)
                .bind()
        }

    private suspend fun peekDownloadInfoByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ) = mutex
        .withLock {
            downloadRepository.getByTag(tag = tag)
                .bind()
        }

    private suspend fun getOrPutDownloadFileEntity(
        url: String,
        urlIsOneTime: Boolean,
        name: String,
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
        encryptionKey: ByteArray?,
        error: DownloadInfoEntity2.Error?,
    ) = kotlin.run {
        val encryptionKeyBase64 = encryptionKey?.let(base64Service::encodeToString)
        mutex
            .withLock {
                val now = Clock.System.now()

                val existingEntry = downloadRepository.getByTag(tag = tag)
                    .bind()
                if (existingEntry != null) {
                    if (
                        existingEntry.name != name ||
                        existingEntry.url != url ||
                        existingEntry.encryptionKeyBase64 != encryptionKeyBase64 ||
                        existingEntry.error != error
                    ) {
                        val newEntry = existingEntry.copy(
                            revisionDate = now,
                            // update fields
                            name = name,
                            url = url,
                            encryptionKeyBase64 = encryptionKeyBase64,
                            error = error,
                        )
                        downloadRepository
                            .put(newEntry)
                            .bind()
                        return@withLock newEntry
                    }
                    return@withLock existingEntry
                }

                val id = cryptoGenerator.uuid()

                val newEntry = DownloadInfoEntity2(
                    id = id,
                    url = url,
                    urlIsOneTime = urlIsOneTime,
                    name = name,
                    localCipherId = tag.localCipherId,
                    remoteCipherId = tag.remoteCipherId,
                    attachmentId = tag.attachmentId,
                    createdDate = now,
                    encryptionKeyBase64 = encryptionKeyBase64,
                )
                 downloadRepository
                     .put(newEntry)
                    .bind()
                newEntry
            }
    }

    private suspend fun replaceDownloadFileEntity(
        id: String,
        error: DownloadInfoEntity2.Error?,
    ) = kotlin.run {
        mutex
            .withLock {
                val now = Clock.System.now()

                val existingEntry = downloadRepository.getById(id = id)
                    .bind()
                if (existingEntry != null) {
                    if (
                        existingEntry.error != error
                    ) {
                        val newEntry = existingEntry.copy(
                            revisionDate = now,
                            // update fields
                            error = error,
                        )
                        downloadRepository
                            .put(newEntry)
                            .bind()
                        return@withLock newEntry
                    }
                    return@withLock existingEntry
                }

                existingEntry
            }
    }
}
