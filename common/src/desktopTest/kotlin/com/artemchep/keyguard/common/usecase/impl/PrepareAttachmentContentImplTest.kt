package com.artemchep.keyguard.common.usecase.impl

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.dragout.readAllBytes
import com.artemchep.keyguard.common.service.dragout.readFrom
import com.artemchep.keyguard.common.service.download.locationUri
import com.artemchep.keyguard.common.service.download.writeVerifiedSource
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolBacking
import com.artemchep.keyguard.common.service.staging.StagingSpoolEvent
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.staging.StagingSpoolObserver
import com.artemchep.keyguard.common.service.staging.StagingSpoolOutcome
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.PrepareAttachmentContent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.RecordingStagingSpoolFactory
import com.artemchep.keyguard.test.testPreparedContentStore
import com.artemchep.keyguard.test.withTempDirectory
import com.artemchep.keyguard.util.io.LocalPath
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer

@OptIn(ExperimentalCoroutinesApi::class)
class PrepareAttachmentContentImplTest {
    @Test
    fun `local download becomes immutable content without metadata or export file`() = withTempDirectory { root ->
        runTest {
            val original = root.resolve("original.txt").toFile().apply { writeBytes(payload) }
            fixture(root, metadataAllowed = false).use { fixture ->
                fixture.prepare(request.copy(localUri = original.toURI().toString())) {}.use { content ->
                    original.writeText("changed after preparation")

                    assertEquals("a_b_c.txt", content.name)
                    assertEquals(payload.size.toLong(), content.size)
                    assertContentEquals(payload, content.readAllBytes())
                    assertContentEquals(payload, content.readAllBytes())
                    assertEquals(0, fixture.metadataRequests.get())
                    assertEquals("changed after preparation", original.readText())
                    assertEquals(listOf("original.txt"), regularFiles(root).map { it.name })
                    assertFalse(root.resolve("attachment-drag").toFile().exists())
                }
            }
        }
    }

    @Test
    fun `missing local download uses the shared source loader without publishing a URI`() = withTempDirectory { root ->
        runTest {
            fixture(root).use { fixture ->
                val missing = request.copy(localUri = root.resolve("missing.txt").toUri().toString())
                fixture.prepare(missing) {}.use { content ->
                    assertEquals(1, fixture.metadataRequests.get())
                    assertContentEquals(payload, content.readAllBytes())
                    assertTrue(regularFiles(root).isEmpty())
                }
            }
        }
    }

    @Test
    fun `locking the vault retires prepared content but an open reader finishes`() = withTempDirectory { root ->
        runTest {
            val session = Job()
            fixture(root, session = session).use { fixture ->
                fixture.prepare(request) {}.use { content ->
                    content.handoff()
                    content.openReader().use { reader ->
                        session.cancel()
                        assertFalse(content.isAvailable())
                        assertFailsWith<IllegalStateException> { content.openReader() }
                        assertContentEquals(payload, reader.readFrom(0))
                    }
                }
            }
        }
    }

    @Test
    fun `a second preparation waits for the first instead of failing`() = withTempDirectory { root ->
        runTest {
            val first = AtomicBoolean(true)
            val sealed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val hold = {
                if (first.getAndSet(false)) {
                    sealed.countDown()
                    release.await()
                }
            }
            fixture(root, onSnapshotSealed = hold).use { fixture ->
                val earlier = async(Dispatchers.Default) { fixture.prepare(request) {} }
                withContext(Dispatchers.IO) { sealed.await() }
                val later = async(Dispatchers.Default) { fixture.prepare(request) {} }
                release.countDown()
                earlier.await().use { assertContentEquals(payload, it.readAllBytes()) }
                later.await().use { assertContentEquals(payload, it.readAllBytes()) }
            }
        }
    }

    @Test
    fun `failed terminal result closes a sealed snapshot and releases its reservation`() = withTempDirectory { root ->
        runTest {
            val failure = IllegalStateException("attachment validation failed")
            fixture(root, failure = failure).use { fixture ->
                val actual = assertFailsWith<IllegalStateException> { fixture.prepare(request) {} }
                assertEquals(failure.message, actual.message)
                assertEquals(1, fixture.staging.snapshots.single().closeCount.get())
                assertTrue(regularFiles(root).isEmpty())
                // Only the failure types are logged; the message may name the attachment.
                val logged = fixture.log.messages.single()
                assertTrue(logged.startsWith("Failed to prepare an attachment: IllegalStateException"))
                assertFalse(failure.message!! in logged)

                // This second preparation fails if the first one leaked its exclusive reservation.
                fixture.prepare(request) {}.use { content ->
                    assertContentEquals(payload, content.readAllBytes())
                }
            }
        }
    }

    @Test
    fun `cancellation after writing a snapshot never publishes content`() = withTempDirectory { root ->
        runTest {
            val cancellation = CancellationException("preparation cancelled")
            fixture(root, failure = cancellation).use { fixture ->
                val actual = assertFailsWith<CancellationException> { fixture.prepare(request) {} }
                assertEquals(cancellation.message, actual.message)
                assertEquals(1, fixture.staging.snapshots.single().closeCount.get())
                assertTrue(regularFiles(root).isEmpty())
                assertTrue(fixture.log.entries.isEmpty())

                fixture.prepare(request) {}.close()
            }
        }
    }

    @Test
    fun `dispatcher cancellation discards content after a successful publication`() = withTempDirectory { root ->
        runTest {
            val cancelReturn = AtomicBoolean()
            fixture(root, onSnapshotSealed = { cancelReturn.set(true) }).use { fixture ->
                val dispatcher = CancelArmedDispatch(StandardTestDispatcher(testScheduler), cancelReturn)
                val preparing = async(dispatcher) { fixture.prepare(request) {} }

                assertFailsWith<CancellationException> { preparing.await() }
                runCurrent()
                assertFalse(cancelReturn.get(), "The return dispatch must consume the cancellation arm")
                assertEquals(1, fixture.staging.snapshots.single().closeCount.get())

                // Also prove the cancellation cleanup returned the only preparation slot.
                fixture.staging.onSnapshotSealed = {}
                fixture.prepare(request) {}.close()
            }
        }
    }

    @Test
    fun `encrypted spill survives a cancelled preparation scope and offer closure`() = withTempDirectory { root ->
        runTest {
            val large = ByteArray(2 * 1024 * 1024 + 117) { index -> ((index * 31 + 7) and 255).toByte() }
            val events = ConcurrentLinkedQueue<StagingSpoolEvent>()
            val factory = DefaultStagingSpoolFactory(
                observer = StagingSpoolObserver { events.add(it) },
                directoryProvider = PrivateTemporaryDirProvider { LocalPath(root.toString()) },
            )
            fixture(root, bytes = large, stagingFactory = factory).use { fixture ->
                val preparationScope = Job()
                val content = withContext(preparationScope) { fixture.prepare(request) {} }
                content.handoff()
                preparationScope.cancel()

                // Both leases authenticate the encrypted backing after its preparation job is gone.
                val sequential = content.openReader()
                val reader = content.openReader()
                content.close()
                // POSIX spills are unlinked at birth; Windows deletes its scratch on close.
                if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                    assertTrue(regularFiles(root).isEmpty())
                }
                assertEquals(0, fixture.staging.snapshots.single().closeCount.get())
                sequential.use { assertContentEquals(large, it.readFrom(0)) }
                reader.use {
                    val tail = ByteArray(117)
                    assertEquals(tail.size, it.readAt((large.size - tail.size).toLong(), tail))
                    assertContentEquals(large.copyOfRange(large.size - tail.size, large.size), tail)
                    // Backward seeking decrypts one chunk under the existing lease, even
                    // though the offer and its preparation job have both closed.
                    assertEquals(tail.size, it.readAt(0, tail))
                    assertContentEquals(large.copyOfRange(0, tail.size), tail)
                }
                runCurrent()

                assertEquals(1, fixture.staging.snapshots.single().closeCount.get())
                assertTrue(events.any {
                    it.purpose == StagingPurpose.PreparedContentPlaintext &&
                        it.backing == StagingSpoolBacking.Spill && it.outcome == StagingSpoolOutcome.Sealed
                })
                assertTrue(regularFiles(root).isEmpty())
                assertFalse(root.resolve("attachment-drag").toFile().exists())
            }
        }
    }

    private fun TestScope.fixture(
        root: Path,
        bytes: ByteArray = payload,
        metadataAllowed: Boolean = true,
        failure: Throwable? = null,
        stagingFactory: StagingSpoolFactory = DefaultStagingSpoolFactory(
            directoryProvider = PrivateTemporaryDirProvider { LocalPath(root.toString()) },
        ),
        onSnapshotSealed: () -> Unit = {},
        session: Job = Job(),
    ): Fixture {
        val metadataRequests = AtomicInteger()
        val nextFailure = AtomicReference(failure)
        val staging = RecordingStagingSpoolFactory(stagingFactory, onSnapshotSealed)
        val log = RecordingLogRepository()
        val store = testPreparedContentStore(
            log = RecordingLogRepository(),
            // Preparation uses real IO threads. Keep expiry delays on real time too;
            // virtual time can otherwise jump five minutes while IO is returning.
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default),
        )
        val prepare = PrepareAttachmentContentImpl(
            downloadAttachmentMetadata = object : DownloadAttachmentMetadata {
                override fun invoke(request: DownloadAttachmentRequest): IO<DownloadAttachmentRequestData> = {
                    check(metadataAllowed)
                    metadataRequests.incrementAndGet()
                    DownloadAttachmentRequestData(
                        "cipher", "remote", "attachment",
                        DownloadAttachmentRequestData.DirectSource(bytes), "attachment.txt", null,
                    )
                }
            },
            downloadAttachmentSourceLoader = object : DownloadAttachmentSourceLoader {
                override fun fileLoader(request: DownloadAttachmentRequestData, writer: DownloadWriter) = flow {
                    val preparationContext = currentCoroutineContext()
                    Buffer().apply { write(bytes) }.use { source ->
                        writer.writeVerifiedSource(source, checkCancellation = preparationContext::ensureActive)
                    }
                    emit(DownloadProgress.Complete(nextFailure.getAndSet(null)?.left() ?: writer.locationUri().right()))
                }
            },
            fileService = FileServiceImpl(),
            store = store,
            stagingSpoolFactory = staging,
            windowCoroutineScope = object : WindowCoroutineScope {
                override val coroutineContext: CoroutineContext = session
            },
            logRepository = log,
        )
        return Fixture(prepare, staging, metadataRequests, log) {
            store.close()
            runCurrent()
        }
    }

    private class Fixture(
        val prepare: PrepareAttachmentContent,
        val staging: RecordingStagingSpoolFactory,
        val metadataRequests: AtomicInteger,
        val log: RecordingLogRepository,
        private val cleanup: () -> Unit,
    ) : AutoCloseable {
        override fun close() = cleanup()
    }

    private class CancelArmedDispatch(
        private val delegate: CoroutineDispatcher,
        private val armed: AtomicBoolean,
    ) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed.compareAndSet(true, false)) context[Job]?.cancel()
            delegate.dispatch(context, block)
        }
    }

    private fun regularFiles(root: Path) = root.toFile().walkTopDown().filter { it.isFile }.toList()

    private companion object {
        val payload = "decrypted attachment".encodeToByteArray()
        val request = PrepareAttachmentContent.Request(
            DownloadAttachmentRequest.ByLocalCipherAttachment("cipher", "remote", "attachment"),
            "a?b*c.txt",
        )
    }
}
