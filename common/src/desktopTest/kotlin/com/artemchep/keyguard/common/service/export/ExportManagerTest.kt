package com.artemchep.keyguard.common.service.export

import arrow.core.right
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.awaitCompleteResult
import com.artemchep.keyguard.common.service.download.writeBytes
import com.artemchep.keyguard.common.service.export.impl.ExportManagerBase
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.util.zip.ZipReader
import com.artemchep.keyguard.util.zip.createZipService
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlinx.io.readString

class ExportManagerTest {
    @Test
    fun encryptedExportPreservesFilterAndReleasesItsScope() = runBlocking {
        val windowJob = Job()
        val window = object : WindowCoroutineScope {
            override val coroutineContext = windowJob + Dispatchers.Default
        }
        val output = Buffer()
        var receivedFilter: DFilter? = null
        val manager = manager(window, object : DirsService {
            override fun saveToDownloads(fileName: String, write: suspend (Sink) -> Unit) = ioEffect {
                assertEquals("keyguard_export_test.zip", fileName)
                write(output)
                "file:///export.zip"
            }
        }) { receivedFilter = it }
        val existingJobs = windowJob.children.toSet()
        try {
            val request = ExportRequest(DFilter.All, "secret", true)
            val result = manager.queue(request)
            assertEquals("file:///export.zip", withTimeout(5000) { result.flow.awaitCompleteResult() }.getOrNull())
            assertEquals(request.filter, receivedFilter)
            val bytes = output.readByteArray()
            ZipReader(Buffer().apply { write(bytes) }, "secret").use { reader ->
                val entry = assertNotNull(reader.nextEntry())
                assertEquals("vault.json", entry.name)
                assertEquals("{\"items\":[]}", entry.source.readString())
                val attachment = assertNotNull(reader.nextEntry())
                assertEquals("attachments/attachment/file.txt", attachment.name)
                assertEquals("attachment bytes", attachment.source.readString())
                assertNull(reader.nextEntry())
            }
            assertFails {
                ZipReader(Buffer().apply { write(bytes) }, "wrong").use { reader ->
                    reader.nextEntry()?.source?.readByteArray()
                }
            }
            withTimeout(5000) { windowJob.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
            assertNull(manager.getProgressFlowByExportId(result.exportId).first())
            // A late subscriber still sees completion after the pool entry is removed.
            assertEquals("file:///export.zip", result.flow.awaitCompleteResult().getOrNull())
        } finally {
            windowJob.cancelAndJoin()
        }
    }

    @Test
    fun cancellationWaitsForWriterCleanupAndReleasesScope() = runBlocking {
        val windowJob = Job()
        val window = object : WindowCoroutineScope {
            override val coroutineContext = windowJob + Dispatchers.Default
        }
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val manager = manager(window, object : DirsService {
            override fun saveToDownloads(fileName: String, write: suspend (Sink) -> Unit) = ioEffect<String?> {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cleaned = true
                }
            }
        }) {}
        val existingJobs = windowJob.children.toSet()
        try {
            val result = manager.queue(ExportRequest(DFilter.All, "secret", false))
            withTimeout(5000) { started.await() }
            manager.cancel(result.exportId)
            val complete = withTimeout(5000) { result.flow.awaitCompleteResult() }
            assertTrue(complete.leftOrNull() is CancellationException)
            assertTrue(cleaned)
            withTimeout(5000) { windowJob.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
        } finally {
            windowJob.cancelAndJoin()
        }
    }

    @Test
    fun writeFailureIsReportedAndReleasesScope() = runBlocking {
        val windowJob = Job()
        val window = object : WindowCoroutineScope {
            override val coroutineContext = windowJob + Dispatchers.Default
        }
        val failure = IllegalStateException("disk full")
        val manager = manager(window, object : DirsService {
            override fun saveToDownloads(fileName: String, write: suspend (Sink) -> Unit) = ioEffect<String?> {
                throw failure
            }
        }) {}
        val existingJobs = windowJob.children.toSet()
        try {
            val result = manager.queue(ExportRequest(DFilter.All, "secret", false))
            val reported = withTimeout(5000) { result.flow.awaitCompleteResult() }.leftOrNull()
            assertIs<IllegalStateException>(reported)
            assertEquals(failure.message, reported.message)
            withTimeout(5000) { windowJob.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
        } finally {
            windowJob.cancelAndJoin()
        }
    }

    @Test
    fun fatalWriteFailureCompletesProgressAndIsRethrown() = runBlocking {
        val windowJob = Job()
        val handled = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> handled.complete(error) }
        val window = object : WindowCoroutineScope {
            override val coroutineContext = windowJob + Dispatchers.Default + handler
        }
        val failure = OutOfMemoryError("Synthetic export failure")
        val manager = manager(window, object : DirsService {
            override fun saveToDownloads(fileName: String, write: suspend (Sink) -> Unit) = ioEffect<String?> {
                throw failure
            }
        }) {}
        val existingJobs = windowJob.children.toSet()
        try {
            val result = manager.queue(ExportRequest(DFilter.All, "secret", false))
            val reported = withTimeout(5000) { result.flow.awaitCompleteResult() }.leftOrNull()
            assertIs<OutOfMemoryError>(reported)
            assertEquals(failure.message, reported.message)
            val uncaught = withTimeout(5000) { handled.await() }
            assertIs<OutOfMemoryError>(uncaught)
            assertEquals(failure.message, uncaught.message)
            withTimeout(5000) { windowJob.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
            assertNull(manager.getProgressFlowByExportId(result.exportId).first())
            // Completion remains available after the failed producer has been removed.
            assertSame(reported, withTimeout(5000) { result.flow.awaitCompleteResult() }.leftOrNull())
        } finally {
            windowJob.cancelAndJoin()
        }
    }

    @Test
    fun alreadyCancelledWindowCompletesProgressAndRemovesEntry() = runBlocking {
        val windowJob = Job()
        val window = object : WindowCoroutineScope {
            override val coroutineContext = windowJob + Dispatchers.Default
        }
        val manager = manager(window, object : DirsService {
            override fun saveToDownloads(fileName: String, write: suspend (Sink) -> Unit) = ioEffect<String?> {
                awaitCancellation()
            }
        }) {}
        try {
            windowJob.cancelAndJoin()
            val result = manager.queue(ExportRequest(DFilter.All, "secret", false))
            val reported = withTimeout(5000) { result.flow.awaitCompleteResult() }.leftOrNull()
            assertIs<CancellationException>(reported)
            withTimeout(5000) { manager.getProgressFlowByExportId(result.exportId).first { it == null } }
            assertTrue(windowJob.children.none())
        } finally {
            windowJob.cancelAndJoin()
        }
    }

    private fun manager(
        window: WindowCoroutineScope,
        dirs: DirsService,
        onFilter: (DFilter) -> Unit,
    ): ExportManagerBase {
        val lockerScope = CoroutineScope(window.coroutineContext)
        return ExportManagerBase(
            windowCoroutineScope = window,
            cryptoGenerator = stub<CryptoGenerator> { "export" },
            exportVaultDataService = object : ExportVaultDataService {
                override suspend fun create(filter: DFilter): ExportVaultData {
                    onFilter(filter)
                    val cipher = DSecret(
                        id = "cipher", accountId = "account", folderId = null,
                        organizationId = null, collectionIds = emptySet(),
                        revisionDate = Instant.fromEpochMilliseconds(0), createdDate = null,
                        archivedDate = null, deletedDate = null, service = BitwardenService(),
                        name = "Fixture", notes = "", favorite = false, reprompt = false,
                        synced = true, type = DSecret.Type.SecureNote,
                        attachments = listOf(DSecret.Attachment.Local(
                            id = "attachment", url = "file:///fixture", fileName = "file.txt", size = 16L,
                        )),
                    )
                    return ExportVaultData(listOf(cipher), emptyList(), emptyList(), emptyList())
                }
                override fun exportJson(data: ExportVaultData) = "{\"items\":[]}"
            },
            dirsService = dirs,
            zipService = createZipService(),
            dateFormatter = stub<DateFormatter> { "test" },
            downloadSourceLoader = object : DownloadAttachmentSourceLoader {
                override fun fileLoader(request: DownloadAttachmentRequestData, writer: DownloadWriter) = flow {
                    writer.writeBytes("attachment bytes".encodeToByteArray())
                    emit(DownloadProgress.Loading(16L, 16L))
                    emit(DownloadProgress.Complete(null.right()))
                }
            },
            downloadAttachmentMetadata = stub {
                ioEffect {
                    DownloadAttachmentRequestData(
                        localCipherId = "cipher", remoteCipherId = null, attachmentId = "attachment",
                        source = DownloadAttachmentRequestData.DirectSource("attachment bytes".encodeToByteArray()),
                        name = "file.txt", encryptionKey = null,
                    )
                }
            },
            vaultSessionLocker = VaultSessionLocker(
                getVaultLockAfterTimeout = stub { flowOf(Duration.INFINITE) },
                clearVaultSession = stub { error("Must not lock during export") },
                scope = lockerScope,
            ),
            onLaunch = {},
        )
    }

    private inline fun <reified T> stub(crossinline invoke: () -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            // Keep the Object methods working, so a failing assertion reports the
            // assertion instead of the stub's own result.
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "toString" -> "stub<" + T::class.java.simpleName + ">"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> invoke()
                }
            } else {
                invoke()
            }
        } as T
}
