package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.decryptToPath
import com.artemchep.keyguard.common.service.crypto.encryptToPath
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import com.artemchep.keyguard.util.io.spool.buildSnapshot
import com.artemchep.keyguard.util.io.spool.copyTo
import com.artemchep.keyguard.util.io.toLocalPath
import com.artemchep.keyguard.util.io.toSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(InternalKeyguardIoApi::class)
class FileCryptoCancellationTest {
    private val key = ByteArray(64) { it.toByte() }
    private val plaintext = ByteArray(4 * 64 * 1024) { (it % 251).toByte() }

    @Test
    fun cancellationDuringInputStopsEncryptionAndClosesSpill() {
        val storage = ObservedScratch()
        val codec = NativeFileEncryptionCodec(NativeCryptoGenerator(), smallSpoolFactory(storage))
        val output = Buffer()
        var reads = 0
        var closed = false
        val bytes = plaintext.toSource()
        assertFailsWith<CancellationException> {
            runBlocking {
                val owner = coroutineContext.job
                val input = object : RawSource {
                    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                        val read = bytes.readAtMostTo(sink, minOf(byteCount, 64L * 1024))
                        if (++reads == 2) owner.cancel()
                        return read
                    }

                    override fun close() {
                        closed = true
                        bytes.close()
                    }
                }.buffered()
                try {
                    codec.encrypt(input, output, key, coroutineContext::ensureActive)
                } finally {
                    // The codec borrows endpoints, including on cancellation.
                    assertEquals(false, closed)
                    input.close()
                }
            }
        }
        assertEquals(2, reads)
        assertTrue(storage.delegate.storedByteCount > 0)
        assertEquals(1, storage.delegate.closeCount)
        assertEquals(0L, output.size)
    }

    @Test
    fun cancellationDuringCiphertextReplayPreservesDestination() {
        // Exercise both encode publication and authenticated decode replay.
        for (encrypt in listOf(true, false)) {
            val root = createTempDirectory("file-crypto-cancellation")
            val target = root.resolve("output.bin")
            val original = "previous output".encodeToByteArray()
            target.writeBytes(original)
            val storage = ObservedScratch()
            val codec = NativeFileEncryptionCodec(NativeCryptoGenerator(), smallSpoolFactory(storage))
            val input = if (encrypt) plaintext else codec.encrypt(plaintext, key)
            try {
                assertFailsWith<CancellationException> {
                    runBlocking {
                        val owner = coroutineContext.job
                        storage.afterRead = { _, read -> if (read > 0) owner.cancel() }
                        input.toSource().use { source ->
                            if (encrypt) {
                                codec.encryptToPath(
                                    source,
                                    target.destination(),
                                    key,
                                    synchronization,
                                    coroutineContext::ensureActive,
                                )
                            } else {
                                codec.decryptToPath(
                                    source,
                                    target.destination(),
                                    key,
                                    synchronization,
                                    coroutineContext::ensureActive,
                                )
                            }
                        }
                    }
                }
                assertEquals(1, storage.sourceCount)
                assertEquals(1, storage.sourceCloseCount)
                assertEquals(1, storage.delegate.closeCount)
                assertContentEquals(original, target.readBytes())
                assertEquals(listOf("output.bin"), root.toFile().list()!!.toList())
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun cancellationDuringEncryptedSpillAuthenticationOrReplayReleasesStorage() {
        // Opening an encrypted spill authenticates through source 1; source 2
        // then decrypts it. Neither pass may depend on the original input.
        for (cancelOnSource in 1..2) {
            val storage = ObservedScratch()
            val output = Buffer()
            assertFailsWith<CancellationException> {
                runBlocking {
                    val owner = coroutineContext.job
                    val probe = coroutineContext::ensureActive
                    val snapshot = smallSpoolFactory(storage).create(
                        purpose = StagingPurpose.DownloadSinkPlaintext,
                        limits = SpoolLimits(1024, plaintext.size.toLong()),
                        checkCancellation = probe,
                        limitExceeded = { error("unexpected size limit") },
                    ).buildSnapshot { it.write(plaintext) }
                    snapshot.use {
                        storage.afterRead = { source, read ->
                            if (source == cancelOnSource && read > 0) owner.cancel()
                        }
                        // Intentionally omit the copy probe: internal spill passes
                        // must observe cancellation themselves.
                        snapshot.copyTo(output)
                    }
                }
            }
            assertEquals(cancelOnSource, storage.sourceCount)
            assertEquals(cancelOnSource, storage.sourceCloseCount)
            assertEquals(1, storage.delegate.closeCount)
            assertEquals(0L, output.size)
        }
    }

    @Test
    fun cancellationAtEndOfInputDoesNotFinalizeOrPublish() {
        val storage = ObservedScratch()
        val codec = NativeFileEncryptionCodec(NativeCryptoGenerator(), smallSpoolFactory(storage))
        val output = Buffer()
        assertFailsWith<CancellationException> {
            runBlocking {
                val owner = coroutineContext.job
                val bytes = plaintext.toSource()
                object : RawSource {
                    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                        val read = bytes.readAtMostTo(sink, byteCount)
                        if (read < 0) owner.cancel()
                        return read
                    }

                    override fun close() = bytes.close()
                }.buffered().use { input ->
                    codec.encrypt(input, output, key, coroutineContext::ensureActive)
                }
            }
        }
        assertEquals(0, storage.sealCount)
        assertEquals(1, storage.delegate.closeCount)
        assertEquals(0L, output.size)
    }

    private fun smallSpoolFactory(storage: ObservedScratch): StagingSpoolFactory {
        val delegate = DefaultStagingSpoolFactory.forTesting(scratchStorageFactory = { storage })
        return object : StagingSpoolFactory {
            override fun create(
                purpose: StagingPurpose,
                limits: SpoolLimits,
                checkCancellation: () -> Unit,
                limitExceeded: (Long) -> Throwable,
            ): ByteStoreWriter = delegate.create(
                purpose = purpose,
                limits = limits.copy(memoryBytes = minOf(1024L, limits.memoryBytes)),
                checkCancellation = checkCancellation,
                limitExceeded = limitExceeded,
            )
        }
    }

    private class ObservedScratch : PrivateTemporaryStorage {
        val delegate = TestPrivateTemporaryStorage()
        var afterRead: (Int, Long) -> Unit = { _, _ -> }
        var sourceCount = 0
        var sourceCloseCount = 0
        var sealCount = 0

        override fun sink(): RawSink = delegate.sink()

        override fun sealForReading() {
            sealCount++
            delegate.sealForReading()
        }

        override fun source(): RawSource {
            val sourceNumber = ++sourceCount
            val source = delegate.source()
            return object : RawSource {
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
                    source.readAtMostTo(sink, byteCount).also { afterRead(sourceNumber, it) }

                override fun close() {
                    sourceCloseCount++
                    source.close()
                }
            }
        }

        override fun close() = delegate.close()
    }

    private fun Path.destination() = AtomicFileDestination(
        root = parent.toLocalPath(),
        relativePath = AtomicRelativePath.fromComponents(AtomicPathComponent.parse(fileName.toString())),
    )

    private val synchronization = SynchronizationPolicy.Required(SyncLevel.FileSynchronized)
}
