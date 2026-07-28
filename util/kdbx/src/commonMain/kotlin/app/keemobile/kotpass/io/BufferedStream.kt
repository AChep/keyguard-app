package app.keemobile.kotpass.io

import okio.Buffer
import okio.ByteString
import okio.Sink
import okio.Source

internal interface BufferedStream : Source {
    /** Underlying [Buffer]. */
    val buffer: Buffer

    /** Returns *true* if there are no more bytes in this source. */
    fun exhausted(): Boolean

    /** Removes a [Byte] from this source and returns it. */
    fun readByte(): Byte

    /** Removes two bytes from this source and returns a big-endian [Short]. */
    fun readShort(): Short

    /** Removes two bytes from this source and returns a little-endian [Short]. */
    fun readShortLe(): Short

    /** Removes four bytes from this source and returns a big-endian [Int]. */
    fun readInt(): Int

    /** Removes four bytes from this source and returns a little-endian [Int]. */
    fun readIntLe(): Int

    /** Removes eight bytes from this source and returns a big-endian [Long]. */
    fun readLong(): Long

    /** Removes eight bytes from this source and returns a little-endian [Long]. */
    fun readLongLe(): Long

    /** Removes all bytes from this and returns them as a byte string. */
    fun readByteString(): ByteString

    /** Removes [byteCount] bytes from this and returns them as a byte string. */
    fun readByteString(byteCount: Long): ByteString

    /** Removes all bytes from this and returns them as a [ByteArray]. */
    fun readByteArray(): ByteArray

    /** Removes [byteCount] bytes from this and returns them as a [ByteArray]. */
    fun readByteArray(byteCount: Long): ByteArray

    /**
     * Removes exactly `sink.length` bytes from this and copies them into `sink`. Throws an
     * [EOFException][java.io.EOFException] if the requested number of bytes cannot be read.
     */
    fun readFully(sink: ByteArray)

    /**
     * Removes up to `byteCount` bytes from this and copies them into `sink` at `offset`.
     * Returns the number of bytes read, or -1 if this source is exhausted.
     */
    fun read(sink: ByteArray, offset: Int, byteCount: Int): Int

    /**
     * Removes `byteCount` bytes from this and appends them to `sink`.
     * Throws an [EOFException][java.io.EOFException] if the requested
     * number of bytes cannot be read.
     */
    fun readFully(sink: Buffer, byteCount: Long)

    /**
     * Removes all bytes from this and appends them to the `sink`.
     * Returns the total number of bytes written to `sink`
     * which will be 0 if this is exhausted.
     */
    fun readAll(sink: Sink): Long

    /** Returns the index of the first `b` in the buffer at or after `fromIndex`. */
    fun indexOf(b: Byte, fromIndex: Long = 0): Long

    /**
     * Returns the index of the first match for `bytes` in the buffer at or after `fromIndex`.
     */
    fun indexOf(bytes: ByteString, fromIndex: Long = 0): Long

    /** Returns true if the bytes at `offset` in this source equal `bytes`. */
    fun rangeEquals(offset: Long, bytes: ByteString): Boolean

    /**
     * Returns *true* when the buffer contains at least [byteCount] bytes,
     * expanding it as necessary. Returns false if the source is exhausted
     * before the requested bytes can be read.
     */
    fun request(byteCount: Long): Boolean

    /**
     * Returns when the buffer contains at least [byteCount] bytes.
     * Throws an [EOFException][java.io.EOFException] if the source
     * is exhausted before the required bytes can be read.
     */
    fun require(byteCount: Long)

    /** Reads and discards [byteCount] bytes from this source. */
    fun skip(byteCount: Long)

    /**
     * Returns new [BufferedStream] that can read data from this
     * [BufferedStream] without consuming it.
     */
    fun peek(): BufferedStream
}
