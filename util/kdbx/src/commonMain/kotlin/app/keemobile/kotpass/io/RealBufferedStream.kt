package app.keemobile.kotpass.io

import app.keemobile.kotpass.extensions.bufferStream
import okio.Buffer
import okio.ByteString
import okio.Sink
import okio.Source
import okio.buffer

/**
 * [BufferedStream] implementation that essentially wraps sealed
 * [BufferedSource][okio.BufferedSource] underneath.
 */
internal class RealBufferedStream(source: Source) : BufferedStream {
    private val bufferedSource = source.buffer()

    override val buffer: Buffer = bufferedSource.buffer

    override fun close() = bufferedSource.close()

    override fun exhausted() = bufferedSource.exhausted()

    override fun indexOf(b: Byte, fromIndex: Long) = bufferedSource.indexOf(b, fromIndex)

    override fun indexOf(bytes: ByteString, fromIndex: Long) = bufferedSource
        .indexOf(bytes, fromIndex)

    override fun rangeEquals(offset: Long, bytes: ByteString) = bufferedSource
        .rangeEquals(offset, bytes)

    override fun read(sink: ByteArray, offset: Int, byteCount: Int) = bufferedSource
        .read(sink, offset, byteCount)

    override fun read(sink: Buffer, byteCount: Long) = bufferedSource.read(sink, byteCount)

    override fun readAll(sink: Sink): Long = bufferedSource.readAll(sink)

    override fun readByte() = bufferedSource.readByte()

    override fun readByteArray() = bufferedSource.readByteArray()

    override fun readByteArray(byteCount: Long) = bufferedSource.readByteArray(byteCount)

    override fun readByteString() = bufferedSource.readByteString()

    override fun readByteString(byteCount: Long) = bufferedSource.readByteString(byteCount)

    override fun readFully(sink: ByteArray) = bufferedSource.readFully(sink)

    override fun readFully(sink: Buffer, byteCount: Long) = bufferedSource
        .readFully(sink, byteCount)

    override fun readInt() = bufferedSource.readInt()

    override fun readIntLe() = bufferedSource.readIntLe()

    override fun readLong() = bufferedSource.readLong()

    override fun readLongLe() = bufferedSource.readLongLe()

    override fun readShort() = bufferedSource.readShort()

    override fun readShortLe() = bufferedSource.readShortLe()

    override fun request(byteCount: Long) = bufferedSource.request(byteCount)

    override fun require(byteCount: Long) = bufferedSource.require(byteCount)

    override fun skip(byteCount: Long) = bufferedSource.skip(byteCount)

    override fun peek() = bufferedSource.peek().bufferStream()

    override fun timeout() = bufferedSource.timeout()
}
