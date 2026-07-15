package com.artemchep.keyguard.crypto

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class EncryptedTemporarySpillStorageJvmTest {
    @Test
    fun emptyPayloadRoundTrips() {
        val output = Buffer()

        EncryptedTemporarySpillStorage.create(TestStorage()).use { spill ->
            spill.seal()
            spill.replayTo(output)
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun rangedWritesAcrossStagingBuffersRoundTrip() {
        val padding = 13
        val plaintext = ByteArray(EncryptedTemporarySpillStorage.STAGING_BUFFER_BYTES * 2 + 37) {
            (it % 251).toByte()
        }
        val source = ByteArray(padding + plaintext.size + padding)
        plaintext.copyInto(source, destinationOffset = padding)
        val output = Buffer()

        EncryptedTemporarySpillStorage.create(TestStorage()).use { spill ->
            spill.write(source, padding, padding + plaintext.size / 2)
            spill.write(source, padding + plaintext.size / 2, padding + plaintext.size)
            spill.seal()
            spill.replayTo(output)
        }

        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun invalidWriteRangesAreRejectedWithoutPoisoningStorage() {
        val plaintext = byteArrayOf(1, 2, 3)
        val output = Buffer()

        EncryptedTemporarySpillStorage.create(TestStorage()).use { spill ->
            assertFailsWith<IllegalArgumentException> { spill.write(plaintext, -1, 1) }
            assertFailsWith<IllegalArgumentException> { spill.write(plaintext, 2, 1) }
            assertFailsWith<IllegalArgumentException> { spill.write(plaintext, 0, 4) }

            spill.write(plaintext, 0, plaintext.size)
            spill.seal()
            spill.replayTo(output)
        }

        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun replayRequiresSealAndCanOnlyHappenOnce() {
        EncryptedTemporarySpillStorage.create(TestStorage()).use { spill ->
            assertFailsWith<IllegalStateException> { spill.replayTo(Buffer()) }
            spill.write(byteArrayOf(1), 0, 1)
            spill.seal()
            spill.replayTo(Buffer())

            assertFailsWith<IllegalStateException> { spill.replayTo(Buffer()) }
        }
    }

    @Test
    fun sealingPreventsMoreWritesAndResealing() {
        EncryptedTemporarySpillStorage.create(TestStorage()).use { spill ->
            spill.seal()

            assertFailsWith<IllegalStateException> { spill.write(byteArrayOf(1), 0, 1) }
            assertFailsWith<IllegalStateException> { spill.seal() }
        }
    }

    @Test
    fun closedStorageRejectsOperationsAndCloseIsIdempotent() {
        val storage = TestStorage()
        val spill = EncryptedTemporarySpillStorage.create(storage)

        spill.close()
        spill.close()

        assertEquals(1, storage.sinkCloseCount)
        assertEquals(1, storage.closeCount)
        assertFailsWith<IllegalStateException> { spill.write(byteArrayOf(1), 0, 1) }
        assertFailsWith<IllegalStateException> { spill.seal() }
        assertFailsWith<IllegalStateException> { spill.replayTo(Buffer()) }
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
        val storage = TestStorage(
            sinkCloseFailure = sinkFailure,
            closeFailure = storageFailure,
        )
        val spill = EncryptedTemporarySpillStorage.create(storage)

        val actual = assertFailsWith<IOException> { spill.close() }

        assertSame(sinkFailure, actual)
        assertEquals(listOf(storageFailure), actual.suppressedExceptions)
        spill.close()
    }

    @Test
    fun closePropagatesStorageFailureWhenItIsTheOnlyFailure() {
        val storageFailure = IOException("storage close failed")
        val spill = EncryptedTemporarySpillStorage.create(
            TestStorage(closeFailure = storageFailure),
        )

        val actual = assertFailsWith<IOException> { spill.close() }

        assertSame(storageFailure, actual)
    }

    @Test
    fun creationClosesStorageWhenOpeningSinkFails() {
        val sinkFailure = IOException("sink open failed")
        val closeFailure = IOException("storage close failed")
        val storage = TestStorage(
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
        val storage = TestStorage(closeFailure = closeFailure)

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
        val storage = TestStorage()
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

    private fun assertTamperingRejected(transform: (ByteArray) -> ByteArray) {
        val storage = TestStorage(tamperOnFirstRewind = transform)
        val output = Buffer()

        EncryptedTemporarySpillStorage.create(storage).use { spill ->
            spill.write(byteArrayOf(1, 2, 3, 4), 0, 4)
            spill.seal()

            assertFailsWith<IOException> { spill.replayTo(output) }
            assertFailsWith<IllegalStateException> { spill.replayTo(output) }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    private class TestStorage(
        private val tamperOnFirstRewind: ((ByteArray) -> ByteArray)? = null,
        private val sinkOpenFailure: Throwable? = null,
        private val sinkCloseFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : PrivateTemporaryStorage {
        private var bytes = ByteArray(0)
        private var rewindCount = 0
        private var sinkClaimed = false
        private var sinkClosed = false
        private var sealed = false
        private var closed = false
        var sinkCloseCount = 0
            private set
        var closeCount = 0
            private set

        private val writableSink = object : RawSink {
            override fun write(source: Buffer, byteCount: Long) {
                check(!sinkClosed)
                bytes += source.readByteArray(byteCount.toInt())
            }

            override fun flush() = Unit

            override fun close() {
                if (sinkClosed) return
                sinkClosed = true
                sinkCloseCount++
                sinkCloseFailure?.let { throw it }
            }
        }

        override fun sink(): RawSink {
            sinkOpenFailure?.let { throw it }
            check(!closed)
            check(!sealed)
            check(!sinkClaimed)
            sinkClaimed = true
            return writableSink
        }

        override fun sealForReading() {
            check(!closed)
            check(!sealed)
            writableSink.close()
            sealed = true
        }

        override fun source(): RawSource {
            check(!closed)
            check(sealed)
            return Buffer().apply { write(bytes) }
        }

        override fun rewind() {
            check(!closed)
            check(sealed)
            if (rewindCount++ == 0) {
                tamperOnFirstRewind?.let { bytes = it(bytes) }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            writableSink.close()
            closeCount++
            closeFailure?.let { throw it }
        }
    }
}
