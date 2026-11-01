package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@OptIn(InternalKeyguardIoApi::class)
class EncryptedChunkedSpillStorageJvmTest {
    private val chunk = EncryptedChunkedSpillStorage.CHUNK_BYTES

    @Test
    fun `sizes around chunk boundaries round trip sequentially and at random offsets`() {
        listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk + 17).forEach { size ->
            val plaintext = payload(size)
            snapshot(plaintext).use { snapshot ->
                assertEquals(size.toLong(), snapshot.size)
                assertContentEquals(plaintext, snapshot.readBytes())
                snapshot.openReader().use { reader ->
                    val random = Random(size)
                    repeat(64) {
                        val position = if (size == 0) 0 else random.nextInt(size)
                        val length = random.nextInt(1, chunk * 2)
                        val output = ByteArray(length)
                        val expected = minOf(length, size - position)
                        assertEquals(expected, reader.readAt(position.toLong(), output))
                        assertContentEquals(
                            plaintext.copyOfRange(position, position + expected),
                            output.copyOf(expected),
                        )
                    }
                    assertEquals(0, reader.readAt(size.toLong(), ByteArray(1)))
                    assertEquals(0, reader.readAt(Long.MAX_VALUE, ByteArray(1)))
                }
            }
        }
    }

    @Test
    fun `independent readers keep their own cursors and partial array ranges`() {
        val plaintext = payload(2 * chunk + 5)
        snapshot(plaintext).use { snapshot ->
            val first = snapshot.openReader()
            val second = snapshot.openReader()
            val output = ByteArray(10) { 0x7f }
            assertEquals(4, first.readAt(chunk - 2L, output, 3, 7))
            assertContentEquals(plaintext.copyOfRange(chunk - 2, chunk + 2), output.copyOfRange(3, 7))
            assertContentEquals(ByteArray(3) { 0x7f }, output.copyOfRange(0, 3))
            assertContentEquals(ByteArray(3) { 0x7f }, output.copyOfRange(7, 10))
            assertEquals(5, second.readAt(2L * chunk, output, 0, 10))
            assertEquals(2, first.readAt(0, output, 0, 2))
            assertContentEquals(plaintext.copyOfRange(0, 2), output.copyOf(2))
            first.close()
            assertFailsWith<IllegalStateException> { first.readAt(0, output) }
            assertEquals(1, second.readAt(0, output, 0, 1))
            second.close()
        }
    }

    @Test
    fun `reading near the end authenticates only the final chunk`() {
        val chunks = 64
        val plaintext = payload(chunks * chunk + 9)
        val storage = CountingStorage(TestPrivateTemporaryStorage())
        snapshot(plaintext, storage).use { snapshot ->
            snapshot.openReader().use { reader ->
                val output = ByteArray(4)
                // The last full chunk, then the short final chunk: one frame read each.
                assertEquals(4, reader.readAt(chunks.toLong() * chunk - 4, output))
                assertContentEquals(plaintext.copyOfRange(chunks * chunk - 4, chunks * chunk), output)
                assertEquals(listOf(EncryptedChunkedSpillStorage.frameLength(chunk)), storage.reads)
                storage.reads.clear()
                assertEquals(4, reader.readAt(plaintext.size - 4L, output))
                assertContentEquals(plaintext.copyOfRange(plaintext.size - 4, plaintext.size), output)
                assertEquals(4, reader.readAt(plaintext.size - 4L, output))
                assertEquals(listOf(EncryptedChunkedSpillStorage.frameLength(9)), storage.reads)
            }
        }
    }

    @Test
    fun `stored bytes never contain the plaintext`() {
        val plaintext = payload(chunk + 100)
        val storage = TestPrivateTemporaryStorage()
        snapshot(plaintext, storage).use {
            assertFalse(storage.storedBytes().containsSubsequence(plaintext.copyOfRange(0, 64)))
        }
    }

    @Test
    fun `a modified ciphertext fails only its own chunk`() {
        val plaintext = payload(3 * chunk)
        val stride = EncryptedChunkedSpillStorage.FRAME_STRIDE
        val storage = TestPrivateTemporaryStorage(tamperOnFirstSource = { it.flipped(stride + 100) })
        snapshot(plaintext, storage).use { snapshot ->
            snapshot.openReader().use { reader ->
                assertEquals(4, reader.readAt(0, ByteArray(4)))
                assertFailsWith<IOException> { reader.readAt(chunk.toLong(), ByteArray(4)) }
                val output = ByteArray(4)
                assertEquals(4, reader.readAt(2L * chunk, output))
                assertContentEquals(plaintext.copyOfRange(2 * chunk, 2 * chunk + 4), output)
            }
        }
    }

    @Test
    fun `a modified authentication tag is rejected`() {
        val plaintext = payload(chunk + 1)
        val tagIndex = EncryptedChunkedSpillStorage.FRAME_STRIDE - 1
        val storage = TestPrivateTemporaryStorage(tamperOnFirstSource = { it.flipped(tagIndex) })
        snapshot(plaintext, storage).use { snapshot ->
            snapshot.openReader().use { reader ->
                assertFailsWith<IOException> { reader.readAt(0, ByteArray(1)) }
            }
        }
    }

    @Test
    fun `swapped chunks are rejected because the IV is bound to the index`() {
        val plaintext = payload(2 * chunk)
        val stride = EncryptedChunkedSpillStorage.FRAME_STRIDE
        val storage = TestPrivateTemporaryStorage(tamperOnFirstSource = { bytes ->
            bytes.copyOfRange(stride, 2 * stride) + bytes.copyOfRange(0, stride)
        })
        snapshot(plaintext, storage).use { snapshot ->
            snapshot.openReader().use { reader ->
                assertFailsWith<IOException> { reader.readAt(0, ByteArray(1)) }
                assertFailsWith<IOException> { reader.readAt(chunk.toLong(), ByteArray(1)) }
            }
        }
    }

    @Test
    fun `truncated storage fails the affected chunk`() {
        val plaintext = payload(chunk + 50)
        val storage = TestPrivateTemporaryStorage(tamperOnFirstSource = { it.copyOf(it.size - 1) })
        snapshot(plaintext, storage).use { snapshot ->
            snapshot.openReader().use { reader ->
                assertEquals(4, reader.readAt(0, ByteArray(4)))
                assertFailsWith<IOException> { reader.readAt(chunk.toLong(), ByteArray(1)) }
            }
        }
    }

    @Test
    fun `closing the snapshot releases storage and rejects readers`() {
        val storage = TestPrivateTemporaryStorage()
        val snapshot = snapshot(payload(10), storage)
        val reader = snapshot.openReader()
        reader.close()
        snapshot.close()
        snapshot.close()
        assertEquals(1, storage.closeCount)
        assertFailsWith<IllegalStateException> { snapshot.openReader() }
    }

    @Test
    fun `an abandoned writer releases its storage`() {
        val storage = TestPrivateTemporaryStorage()
        EncryptedChunkedSpillStorage.create(storage).use { writer ->
            writer.sink().write(payload(chunk + 1))
        }
        assertEquals(1, storage.closeCount)
    }

    private class CountingStorage(
        private val delegate: PrivateTemporaryStorage,
    ) : PrivateTemporaryStorage by delegate {
        val reads = mutableListOf<Int>()

        override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
            reads += endIndex - startIndex
            return delegate.readAt(position, data, startIndex, endIndex)
        }
    }
}

@OptIn(InternalKeyguardIoApi::class)
class EncryptedChunkedSpillStorageFailureJvmTest {
    private val chunk = EncryptedChunkedSpillStorage.CHUNK_BYTES

    @Test
    fun `cancelled sealing cannot be retried with erased plaintext`() {
        val storage = TestPrivateTemporaryStorage()
        val cancellation = CancellationException("cancel sealing")
        var checksUntilCancellation = 0
        EncryptedChunkedSpillStorage.create(storage, checkCancellation = {
            if (checksUntilCancellation > 0 && --checksUntilCancellation == 0) throw cancellation
        }).use { writer ->
            writer.sink().use { it.write(byteArrayOf(1, 2, 3)) }
            // Fail after encrypting the final partial chunk, before writing it.
            checksUntilCancellation = 3
            assertSame(cancellation, assertFailsWith<CancellationException> { writer.seal() })
            assertFailsWith<IllegalStateException> { writer.seal() }
        }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `partial ciphertext write failures prevent further writes and sealing`() {
        // Non-Exception failures must also poison a writer whose chunk was consumed.
        for (failure in listOf(IOException("write failed"), AssertionError("write failed"))) {
            val storage = TestPrivateTemporaryStorage()
            var failWrite = true
            val failing = object : PrivateTemporaryStorage by storage {
                override fun sink(): RawSink {
                    val sink = storage.sink()
                    return object : RawSink by sink {
                        override fun write(source: Buffer, byteCount: Long) {
                            sink.write(source, byteCount)
                            if (failWrite) throw failure
                        }
                    }
                }
            }
            EncryptedChunkedSpillStorage.create(failing).use { writer ->
                val sink = writer.sink()
                assertSame(failure, assertFailsWith<Throwable> { sink.write(payload(chunk)) })
                failWrite = false
                assertFailsWith<IllegalStateException> { sink.write(payload(chunk)) }
                assertFailsWith<IllegalStateException> { writer.seal() }
            }
            assertEquals(1, storage.closeCount)
        }
    }

    @Test
    fun `flush failures prevent sealing even after the storage recovers`() {
        val storage = TestPrivateTemporaryStorage()
        val failure = IOException("flush failed")
        var failFlush = true
        val failing = object : PrivateTemporaryStorage by storage {
            override fun sink(): RawSink {
                val sink = storage.sink()
                return object : RawSink by sink {
                    override fun flush() {
                        if (failFlush) throw failure
                        sink.flush()
                    }
                }
            }
        }
        EncryptedChunkedSpillStorage.create(failing).use { writer ->
            val sink = writer.sink()
            sink.write(byteArrayOf(1, 2, 3))
            assertSame(failure, assertFailsWith<IOException> { sink.flush() })
            failFlush = false
            assertFailsWith<IllegalStateException> { sink.flush() }
            assertFailsWith<IllegalStateException> { writer.seal() }
        }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `storage sealing failure permanently rejects retrying seal`() {
        val storage = TestPrivateTemporaryStorage()
        val failure = IOException("seal failed")
        var failSeal = true
        val failing = object : PrivateTemporaryStorage by storage {
            override fun sealForReading() {
                if (failSeal) throw failure
                storage.sealForReading()
            }
        }
        EncryptedChunkedSpillStorage.create(failing).use { writer ->
            writer.sink().use { it.write(byteArrayOf(1, 2, 3)) }
            assertSame(failure, assertFailsWith<IOException> { writer.seal() })
            failSeal = false
            assertFailsWith<IllegalStateException> { writer.seal() }
        }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `sealed snapshots never consult the writer cancellation probe`() {
        val plaintext = payload(chunk + 3)
        val storage = TestPrivateTemporaryStorage()
        var cancelled = false
        snapshot(plaintext, storage, checkCancellation = {
            if (cancelled) throw CancellationException("writer operation ended")
        }).use { snapshot ->
            // The snapshot outlives the operation that wrote it.
            cancelled = true
            assertContentEquals(plaintext, snapshot.readBytes())
            snapshot.openReader().use { reader ->
                val output = ByteArray(4)
                assertEquals(4, reader.readAt(chunk - 1L, output))
                assertContentEquals(plaintext.copyOfRange(chunk - 1, chunk + 3), output)
            }
        }
        assertEquals(1, storage.closeCount)
    }
}

private fun snapshot(
    plaintext: ByteArray,
    storage: PrivateTemporaryStorage = TestPrivateTemporaryStorage(),
    checkCancellation: () -> Unit = {},
): RandomAccessByteSnapshot = EncryptedChunkedSpillStorage.create(storage, checkCancellation).use { writer ->
    writer.sink().use { sink ->
        // Uneven writes cross chunk boundaries at arbitrary positions.
        var offset = 0
        var step = 1
        while (offset < plaintext.size) {
            val end = minOf(plaintext.size, offset + step)
            sink.write(plaintext, offset, end)
            offset = end
            step = step * 3 + 7
        }
    }
    writer.seal()
}

private fun payload(size: Int) = ByteArray(size) { ((it * 31 + 7) and 255).toByte() }

private fun ByteArray.flipped(index: Int) = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
