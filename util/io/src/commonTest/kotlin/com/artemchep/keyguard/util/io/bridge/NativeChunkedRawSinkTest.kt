package com.artemchep.keyguard.util.io.bridge

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeChunkedRawSinkTest {
    @Test
    fun closeEmitsOnePendingChunkAndErasesIt() {
        var callbackChunk: ByteArray? = null
        var callbackLength = -1
        val sink = NativeChunkedRawSink { chunk, length ->
            callbackChunk = chunk
            callbackLength = length
            assertContentEquals(byteArrayOf(1, 2, 3), chunk.copyOf(length))
        }

        sink.write(Buffer().apply { write(byteArrayOf(1, 2, 3)) }, 3)
        sink.close()
        sink.close()

        assertEquals(3, callbackLength)
        assertTrue(callbackChunk!!.all { it == 0.toByte() })
    }

    @Test
    fun discardNeverEmitsPendingBytesAndIsIdempotent() {
        var callbacks = 0
        val sink = NativeChunkedRawSink { _, _ -> callbacks += 1 }
        sink.write(Buffer().apply { write(byteArrayOf(1, 2, 3)) }, 3)

        sink.discard()
        sink.discard()

        assertEquals(0, callbacks)
        assertFailsWith<IllegalStateException> {
            sink.write(Buffer().apply { writeByte(4) }, 1)
        }
    }

    @Test
    fun discardModeDrainsAndErasesWithoutEmission() {
        var callbacks = 0
        val sink = NativeChunkedRawSink { _, _ -> callbacks += 1 }
        sink.write(Buffer().apply { write(byteArrayOf(1, 2, 3)) }, 3)
        sink.beginDiscarding()
        val source = Buffer().apply { write(byteArrayOf(4, 5, 6)) }

        sink.write(source, source.size)
        sink.flush()
        sink.close()

        assertEquals(0L, source.size)
        assertEquals(0, callbacks)
        assertFailsWith<IllegalStateException> {
            sink.write(Buffer().apply { writeByte(7) }, 1)
        }
    }

    @Test
    fun callbackFailureIsTerminalAndNeverRetried() {
        val original = TestException()
        var callbacks = 0
        var callbackChunk: ByteArray? = null
        val sink = NativeChunkedRawSink { chunk, _ ->
            callbacks += 1
            callbackChunk = chunk
            throw original
        }
        sink.write(Buffer().apply { write(byteArrayOf(1, 2, 3)) }, 3)

        val thrown = assertFailsWith<TestException> {
            sink.flush()
        }
        assertTrue(thrown === original)
        assertTrue(callbackChunk!!.all { it == 0.toByte() })
        assertFailsWith<IllegalStateException> {
            sink.flush()
        }
        sink.close()
        sink.discard()
        assertEquals(1, callbacks)
    }

    @Test
    fun callbackFailureConsumesBytesThatWereWaitingBehindTheFailedChunk() {
        val original = TestException()
        var callbacks = 0
        val sink = NativeChunkedRawSink { _, _ ->
            callbacks += 1
            throw original
        }
        sink.write(
            Buffer().apply { write(ByteArray(NATIVE_IO_CHUNK_SIZE)) },
            NATIVE_IO_CHUNK_SIZE.toLong(),
        )
        val remainingSource = Buffer().apply {
            write(byteArrayOf(1, 2, 3))
        }

        val thrown = assertFailsWith<TestException> {
            sink.write(remainingSource, remainingSource.size)
        }

        assertTrue(thrown === original)
        assertEquals(0L, remainingSource.size)
        assertEquals(1, callbacks)
        sink.close()
    }

    private class TestException : RuntimeException()
}
