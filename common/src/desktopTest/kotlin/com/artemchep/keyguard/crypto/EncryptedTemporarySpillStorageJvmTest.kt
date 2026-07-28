package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.foundation.io.copyTo
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class EncryptedTemporarySpillStorageJvmTest {
    @Test
    fun emptyPayloadRoundTrips() {
        val snapshot = EncryptedTemporarySpillStorage.create(TestPrivateTemporaryStorage()).use { writer ->
            writer.seal()
        }

        snapshot.use {
            assertContentEquals(byteArrayOf(), snapshot.readBytes())
        }
    }

    @Test
    fun rangedWritesAcrossStagingBuffersRoundTrip() {
        val padding = 13
        val plaintext = ByteArray(EncryptedTemporarySpillStorage.STAGING_BUFFER_BYTES * 2 + 37) {
            (it % 251).toByte()
        }
        val source = ByteArray(padding + plaintext.size + padding)
        plaintext.copyInto(source, destinationOffset = padding)

        val snapshot = EncryptedTemporarySpillStorage.create(TestPrivateTemporaryStorage()).use { writer ->
            writer.sink().use { sink ->
                sink.write(source, padding, padding + plaintext.size / 2)
                sink.write(source, padding + plaintext.size / 2, padding + plaintext.size)
            }
            writer.seal()
        }

        snapshot.use {
            assertEquals(plaintext.size.toLong(), snapshot.size)
            assertContentEquals(plaintext, snapshot.readBytes())
        }
    }

    @Test
    fun encryptedSnapshotCanBeReadMoreThanOnce() {
        val plaintext = ByteArray(EncryptedTemporarySpillStorage.STAGING_BUFFER_BYTES + 17) {
            (it % 251).toByte()
        }
        val snapshot = encryptedSnapshot(plaintext)

        snapshot.use {
            assertContentEquals(plaintext, snapshot.readBytes())
            assertContentEquals(plaintext, snapshot.readBytes())
        }
    }

    @Test
    fun sealingPreventsMoreWritesAndResealing() {
        val writer = EncryptedTemporarySpillStorage.create(TestPrivateTemporaryStorage())
        val sink = writer.sink()
        sink.close()
        val snapshot = writer.seal()
        try {
            assertFailsWith<IllegalStateException> { writer.sink() }
            assertFailsWith<IllegalStateException> { writer.seal() }
        } finally {
            writer.close()
            snapshot.close()
        }
    }

    @Test
    fun closedWriterRejectsOperationsAndCloseIsIdempotent() {
        val storage = TestPrivateTemporaryStorage()
        val writer = EncryptedTemporarySpillStorage.create(storage)

        writer.close()
        writer.close()

        assertEquals(1, storage.sinkCloseCount)
        assertEquals(1, storage.closeCount)
        assertFailsWith<IllegalStateException> { writer.sink() }
        assertFailsWith<IllegalStateException> { writer.seal() }
    }

    @Test
    fun closeDiscardsBufferedPlaintextWithoutThrowing() {
        val storage = TestPrivateTemporaryStorage()
        val writer = EncryptedTemporarySpillStorage.create(storage)
        writer.sink().write(byteArrayOf(1, 2, 3))

        writer.close()

        assertEquals(1, storage.closeCount)
    }

    @Test
    fun changedCiphertextIsRejectedBeforeOutput() {
        assertTamperingRejected { bytes ->
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            bytes
        }
    }

    @Test
    fun truncatedCiphertextIsRejectedBeforeOutput() {
        assertTamperingRejected { bytes -> bytes.copyOf(bytes.size - 1) }
    }

    @Test
    fun appendedCiphertextIsRejectedBeforeOutput() {
        assertTamperingRejected { bytes -> bytes + 0x5a.toByte() }
    }

    @Test
    fun closePreservesPrimaryFailureAndSuppressesLaterFailure() {
        val sinkFailure = IOException("sink close failed")
        val storageFailure = IOException("storage close failed")
        val storage = TestPrivateTemporaryStorage(
            sinkCloseFailure = sinkFailure,
            closeFailure = storageFailure,
        )
        val writer = EncryptedTemporarySpillStorage.create(storage)

        val actual = assertFailsWith<IOException> { writer.close() }

        assertSame(sinkFailure, actual)
        assertEquals(listOf(storageFailure), actual.suppressedExceptions)
        writer.close()
    }

    @Test
    fun closePropagatesStorageFailureWhenItIsTheOnlyFailure() {
        val storageFailure = IOException("storage close failed")
        val writer = EncryptedTemporarySpillStorage.create(
            TestPrivateTemporaryStorage(closeFailure = storageFailure),
        )

        val actual = assertFailsWith<IOException> { writer.close() }

        assertSame(storageFailure, actual)
    }

    @Test
    fun creationClosesStorageWhenOpeningSinkFails() {
        val sinkFailure = IOException("sink open failed")
        val closeFailure = IOException("storage close failed")
        val storage = TestPrivateTemporaryStorage(
            sinkOpenFailure = sinkFailure,
            closeFailure = closeFailure,
        )

        val actual = assertFailsWith<IOException> {
            EncryptedTemporarySpillStorage.create(storage)
        }

        assertSame(sinkFailure, actual)
        assertEquals(1, storage.closeCount)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
    }

    @Test
    fun creationClosesStorageWhenInitialKeyGenerationFails() {
        val randomFailure = IOException("key generation failed")
        val closeFailure = IOException("storage close failed")
        val storage = TestPrivateTemporaryStorage(closeFailure = closeFailure)

        val actual = assertFailsWith<IOException> {
            EncryptedTemporarySpillStorage.create(
                storage = storage,
                randomBytes = { throw randomFailure },
            )
        }

        assertSame(randomFailure, actual)
        assertEquals(1, storage.closeCount)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
    }

    @Test
    fun creationClosesStorageAndClearsKeyMaterialWhenIvGenerationFails() {
        val ivFailure = IOException("IV generation failed")
        val storage = TestPrivateTemporaryStorage()
        val keyMaterial = ByteArray(EncryptedTemporarySpillStorage.STAGING_KEY_BYTES) { index ->
            (index + 1).toByte()
        }
        var requestCount = 0

        val actual = assertFailsWith<IOException> {
            EncryptedTemporarySpillStorage.create(
                storage = storage,
                randomBytes = { length ->
                    when (requestCount++) {
                        0 -> {
                            assertEquals(EncryptedTemporarySpillStorage.STAGING_KEY_BYTES, length)
                            keyMaterial
                        }

                        else -> {
                            assertEquals(FileEncryptionFormat.IV_LENGTH, length)
                            throw ivFailure
                        }
                    }
                },
            )
        }

        assertSame(ivFailure, actual)
        assertEquals(2, requestCount)
        assertContentEquals(ByteArray(keyMaterial.size), keyMaterial)
        assertEquals(1, storage.closeCount)
    }

    private fun encryptedSnapshot(plaintext: ByteArray) =
        EncryptedTemporarySpillStorage.create(TestPrivateTemporaryStorage()).use { writer ->
            writer.sink().use { sink -> sink.write(plaintext) }
            writer.seal()
        }

    private fun assertTamperingRejected(transform: (ByteArray) -> ByteArray) {
        val storage = TestPrivateTemporaryStorage(tamperOnFirstSource = transform)
        val snapshot = EncryptedTemporarySpillStorage.create(storage).use { writer ->
            writer.sink().use { sink -> sink.write(byteArrayOf(1, 2, 3, 4)) }
            writer.seal()
        }
        val output = Buffer()

        snapshot.use {
            assertFailsWith<IOException> { snapshot.copyTo(output) }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }
}
