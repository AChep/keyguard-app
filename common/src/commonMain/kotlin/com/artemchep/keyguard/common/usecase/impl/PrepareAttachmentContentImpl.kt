package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadSnapshotWriter
import com.artemchep.keyguard.common.service.dragout.PreparedContentStore
import com.artemchep.keyguard.common.service.dragout.dragOutFileName
import com.artemchep.keyguard.common.service.download.awaitCompleteResult
import com.artemchep.keyguard.common.service.download.writeVerifiedSource
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.PrepareAttachmentContent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.service.dragout.PreparedContent
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class PrepareAttachmentContentImpl internal constructor(
    private val downloadAttachmentMetadata: DownloadAttachmentMetadata,
    private val downloadAttachmentSourceLoader: DownloadAttachmentSourceLoader,
    private val fileService: FileService,
    private val store: PreparedContentStore,
    private val stagingSpoolFactory: StagingSpoolFactory,
    /** The vault session's scope: locking retires the content it decrypted. */
    private val windowCoroutineScope: WindowCoroutineScope,
    private val logRepository: LogRepository,
) : PrepareAttachmentContent {
    // The store admits one preparation at a time, and a promised drag starts before
    // an earlier one finishes preparing. Later preparations wait their turn.
    private val mutex = Mutex()

    // Ownership cleanup also covers cancellation while dispatching a successful result.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    override suspend fun invoke(
        request: PrepareAttachmentContent.Request,
        onProgress: (DownloadProgress.Loading) -> Unit,
    ): PreparedContent {
        var prepared: PreparedContent? = null
        try {
            val session = windowCoroutineScope.coroutineContext.job
            return mutex.withLock {
                withContext(Dispatchers.IO) {
                    store.reserve(request.maximumBytes, owner = session).use { reservation ->
                        DownloadSnapshotWriter(stagingSpoolFactory, reservation.limits).use { writer ->
                            onProgress(DownloadProgress.Loading())
                            val context = coroutineContext
                            val local = request.localUri?.let { uri ->
                                runCatchingNonFatal { fileService.readFromFile(uri) }.getOrNull()
                            }
                            if (local != null) {
                                local.use { source ->
                                    writer.writeVerifiedSource(
                                        source = source,
                                        checkCancellation = context::ensureActive,
                                        onProgress = { onProgress(DownloadProgress.Loading(downloaded = it)) },
                                    )
                                }
                            } else {
                                val metadata = downloadAttachmentMetadata(request.attachment).bind()
                                downloadAttachmentSourceLoader.fileLoader(metadata, writer)
                                    .onEach { progress ->
                                        if (progress is DownloadProgress.Loading) onProgress(progress)
                                    }
                                    .awaitCompleteResult()
                                    .onLeft { throw it }
                            }
                            context.ensureActive()
                            val snapshot = writer.takeSnapshot()
                            try {
                                context.ensureActive()
                                reservation.publish(dragOutFileName(request.name), snapshot).also { prepared = it }
                            } catch (failure: Throwable) {
                                runCatchingNonFatal { snapshot.close() }
                                    .onFailure(failure::addSuppressed)
                                throw failure
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            prepared?.close()
            if (failure !is CancellationException) reportFailure(failure)
            throw failure
        }
    }

    // Exported logs must not carry names or URLs, so only the failure types are logged.
    @Suppress("TooGenericExceptionCaught")
    private fun reportFailure(failure: Throwable) {
        val types = generateSequence(failure) { it.cause }
            .take(MAX_LOGGED_CAUSES)
            .joinToString(separator = " <- ") { it::class.simpleName ?: "Throwable" }
        try {
            logRepository.post(
                tag = TAG,
                message = "Failed to prepare an attachment: $types",
                level = LogLevel.WARNING,
            )
        } catch (e: Throwable) {
            failure.addSuppressed(e)
        }
    }

    private companion object {
        const val TAG = "PrepareAttachmentContent"
        const val MAX_LOGGED_CAUSES = 4
    }
}
