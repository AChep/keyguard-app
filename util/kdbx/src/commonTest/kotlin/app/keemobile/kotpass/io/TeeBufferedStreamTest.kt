package app.keemobile.kotpass.io

import app.keemobile.kotpass.extensions.teeBufferStream
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeeBufferedStreamTest {
    @Test
    fun handoffPreservesSnapshotAndPrefetchedBodyWithoutFurtherCapture() {
        val bytes = ByteArray(32 * 1024) { it.toByte() }
        for (chunkSize in listOf(1, 8192)) {
            val upstream = CaptureTestSource(bytes, chunkSize)
            val mirror = Buffer()
            val headerSource = upstream.teeBufferStream(mirror)
            headerSource.readByteString(17)
            val header = mirror.snapshot()
            if (chunkSize > 17) assertTrue(upstream.bytesRead > 17)

            val body = headerSource.finishCapture()
            assertEquals(0L, mirror.size)
            val output = Buffer()
            while (body.read(output, 113L) != -1L) {
                assertEquals(0L, mirror.size)
            }

            assertContentEquals(bytes.copyOfRange(0, 17), header.toByteArray())
            assertContentEquals(bytes.copyOfRange(17, bytes.size), output.readByteArray())
            body.close()
            headerSource.close()
            assertEquals(1, upstream.closeCalls)
        }
    }

    @Test
    fun handedOffReadAllWritesIncrementally() {
        val bytes = ByteArray(64 * 1024) { it.toByte() }
        val upstream = CaptureTestSource(bytes, 8192)
        val mirror = Buffer()
        val headerSource = upstream.teeBufferStream(mirror)
        headerSource.readByte()
        val body = headerSource.finishCapture()
        var written = 0L
        val sink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                if (written == 0L) assertTrue(upstream.bytesRead < bytes.size)
                assertEquals(0L, mirror.size)
                source.skip(byteCount)
                written += byteCount
            }

            override fun flush() = Unit
            override fun close() = Unit
            override fun timeout() = Timeout.NONE
        }

        assertEquals(bytes.size - 1L, body.readAll(sink))
        assertEquals(bytes.size - 1L, written)
        headerSource.close()
        body.close()
        assertEquals(1, upstream.closeCalls)
    }
}

private class CaptureTestSource(
    bytes: ByteArray,
    private val chunkSize: Int,
) : Source {
    private val input = Buffer().write(bytes)
    var bytesRead = 0L
        private set
    var closeCalls = 0
        private set

    override fun read(sink: Buffer, byteCount: Long): Long =
        input.read(sink, minOf(byteCount, chunkSize.toLong())).also {
            if (it > 0) bytesRead += it
        }

    override fun timeout() = Timeout.NONE

    override fun close() {
        closeCalls++
        input.close()
    }
}
