package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.FileEncryptionCodecJvm
import com.artemchep.keyguard.crypto.FileEncryptionFormat
import com.artemchep.keyguard.crypto.NativeFileCrypto
import com.artemchep.keyguard.crypto.TestPrivateTemporaryStorage
import com.artemchep.keyguard.crypto.containsSubsequence
import com.artemchep.keyguard.crypto.readBytes
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.test.RecordingStagingSpoolFactory
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(InternalKeyguardIoApi::class)
class DownloadSnapshotWriterTest {
    private val codec = FileEncryptionCodecJvm(CryptoGeneratorJvm())

    @Test
    fun `snapshot ownership transfers once and destination close does not close a taken snapshot`() {
        val writer = DownloadSnapshotWriter(DefaultStagingSpoolFactory())
        val bytes = "prepared attachment".encodeToByteArray()
        assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
        assertNull(writer.locationUri())

        writer.writeBytes(bytes)
        val snapshot = writer.takeSnapshot()
        writer.close()
        writer.close()

        snapshot.use {
            assertEquals(bytes.size.toLong(), snapshot.size)
            assertContentEquals(bytes, snapshot.readBytes())
            assertContentEquals(bytes, snapshot.readBytes())
            assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
        }
    }

    @Test
    fun `verified source uses one confidential spool and reports full progress`() {
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)
        val bytes = ByteArray(128 * 1024 + 1) { it.toByte() }
        val progress = mutableListOf<Long>()
        val writer = DownloadSnapshotWriter(factory, spillLimits(bytes.size.toLong()))

        writer.use {
            writer.writeVerifiedSource(Buffer().apply { write(bytes) }, onProgress = progress::add)
            writer.takeSnapshot().use { snapshot -> assertContentEquals(bytes, snapshot.readBytes()) }
        }

        assertEquals(listOf(StagingPurpose.PreparedContentPlaintext), factory.purposes)
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(1, storage.closeCount)
        assertFalse(storage.storedBytes().containsSubsequence(bytes))
    }

    @Test
    fun `encrypted source success captures one verified snapshot`() {
        val plaintext = ByteArray(96 * 1024 + 7) { it.toByte() }
        val key = ByteArray(64) { it.toByte() }
        val frame = codec.encrypt(plaintext, key)
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)

        DownloadSnapshotWriter(factory, spillLimits(plaintext.size.toLong())).use { writer ->
            writer.writeSource(Buffer().apply { write(frame) }, key, codec, factory)
            writer.takeSnapshot().use { snapshot -> assertContentEquals(plaintext, snapshot.readBytes()) }
        }

        assertEquals(listOf(StagingPurpose.PreparedContentPlaintext), factory.purposes)
        assertEquals(1, storage.closeCount)
        assertFalse(storage.storedBytes().containsSubsequence(plaintext))
    }

    @Test
    fun `bad mac truncation and authenticated invalid padding never expose a snapshot`() {
        val key = ByteArray(64) { it.toByte() }
        val frame = codec.encrypt(ByteArray(64 * 1024 + 1) { it.toByte() }, key)
        val badMac = frame.copyOf().apply { this[17] = (this[17].toInt() xor 1).toByte() }
        val truncated = frame.copyOf(frame.size - 1)
        val invalidPadding = withInvalidAuthenticatedPadding(frame.copyOf(), key)

        listOf(badMac, truncated, invalidPadding).forEach { invalid ->
            val storage = TestPrivateTemporaryStorage()
            val factory = recordingFactory(storage)
            DownloadSnapshotWriter(factory, spillLimits(frame.size.toLong())).use { writer ->
                assertFails { writer.writeSource(Buffer().apply { write(invalid) }, key, codec, factory) }
                assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
            }
            if (invalid === invalidPadding) assertEquals(1, storage.closeCount)
        }
    }

    @Test
    fun `partial plaintext remains private until producer validation finishes even for fatal errors`() {
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)
        val failure = AssertionError("validation failed after provisional plaintext")
        val writer = DownloadSnapshotWriter(factory, spillLimits(1024))
        val failingCodec = object : FileEncryptionCodec by codec {
            override fun decrypt(input: Source, output: Sink, key: ByteArray, checkCancellation: () -> Unit) {
                output.write("provisional".encodeToByteArray())
                assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
                throw failure
            }
        }

        val actual = assertFailsWith<AssertionError> {
            writer.writeSource(Buffer(), byteArrayOf(1), failingCodec, factory)
        }
        writer.close()

        assertSame(failure, actual)
        assertEquals(1, storage.closeCount)
        assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
    }

    @Test
    fun `sealed encrypted snapshot does not consult the preparation cancellation probe`() {
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)
        val plaintext = ByteArray(70_000) { it.toByte() }
        val writer = DownloadSnapshotWriter(factory, spillLimits(plaintext.size.toLong()))
        var cancelled = false
        writer.writeVerifiedSource(
            Buffer().apply { write(plaintext) },
            checkCancellation = { if (cancelled) throw CancellationException("source presentation closed") },
        )
        val snapshot = writer.takeSnapshot()
        writer.close()
        cancelled = true

        snapshot.use {
            assertContentEquals(plaintext, snapshot.readBytes())
            assertContentEquals(plaintext, snapshot.readBytes())
        }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `cancellation while sealing destroys the completed but unpublished snapshot`() {
        val storage = TestPrivateTemporaryStorage()
        val delegate = recordingFactory(storage)
        var cancelled = false
        val factory = object : StagingSpoolFactory {
            override fun create(
                purpose: StagingPurpose,
                limits: SpoolLimits,
                checkCancellation: () -> Unit,
                limitExceeded: (Long) -> Throwable,
            ): ByteStoreWriter {
                val spool = delegate.create(purpose, limits, checkCancellation, limitExceeded)
                return object : ByteStoreWriter by spool {
                    override fun seal() = spool.seal().also { cancelled = true }
                }
            }
        }

        DownloadSnapshotWriter(factory, spillLimits(1024)).use { writer ->
            assertFailsWith<CancellationException> {
                writer.writeVerifiedSource(
                    Buffer().apply { write("private".encodeToByteArray()) },
                    checkCancellation = { if (cancelled) throw CancellationException("cancelled") },
                )
            }
            assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
        }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `closing an untaken snapshot releases its encrypted backing and rejects another write`() {
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)
        val writer = DownloadSnapshotWriter(factory, spillLimits(1024))
        writer.writeBytes("private".encodeToByteArray())

        assertFailsWith<IllegalStateException> { writer.writeBytes(byteArrayOf(1)) }
        writer.close()
        writer.close()

        assertEquals(1, storage.closeCount)
        assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
    }

    @Test
    fun `closing a destination during preparation prevents publication`() {
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)
        val writer = DownloadSnapshotWriter(factory, spillLimits(1024))
        val closingCodec = object : FileEncryptionCodec by codec {
            override fun decrypt(input: Source, output: Sink, key: ByteArray, checkCancellation: () -> Unit) {
                output.write("private".encodeToByteArray())
                output.flush()
                writer.close()
                checkCancellation()
            }
        }

        assertFailsWith<IllegalStateException> {
            writer.writeSource(Buffer(), byteArrayOf(1), closingCodec, factory)
        }

        assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `plaintext limit failure does not expose or retain a snapshot`() {
        val storage = TestPrivateTemporaryStorage()
        val factory = recordingFactory(storage)
        DownloadSnapshotWriter(factory, spillLimits(4)).use { writer ->
            assertFailsWith<IOException> {
                writer.writeVerifiedSource(Buffer().apply { write(ByteArray(5)) })
            }
            assertFailsWith<IllegalStateException> { writer.takeSnapshot() }
        }
    }
}

private fun spillLimits(maximum: Long) = SpoolLimits(memoryBytes = 0, maximumBytes = maximum)

@OptIn(InternalKeyguardIoApi::class)
private fun recordingFactory(storage: TestPrivateTemporaryStorage) =
    RecordingStagingSpoolFactory(DefaultStagingSpoolFactory.forTesting(scratchStorageFactory = { storage }))

private fun withInvalidAuthenticatedPadding(frame: ByteArray, key: ByteArray): ByteArray {
    frame[frame.size - 17] = (frame[frame.size - 17].toInt() xor 1).toByte()
    val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
    val iv = frame.copyOfRange(1, 17)
    val ciphertext = frame.copyOfRange(FileEncryptionFormat.HEADER_LENGTH, frame.size)
    val mac = NativeFileCrypto.hmacSha256(keys.macKey, iv, ciphertext)
    try {
        mac.copyInto(frame, destinationOffset = 17)
        return frame
    } finally {
        with(NativeFileCrypto) { keys.clear() }
        iv.fill(0)
        ciphertext.fill(0)
        mac.fill(0)
    }
}
