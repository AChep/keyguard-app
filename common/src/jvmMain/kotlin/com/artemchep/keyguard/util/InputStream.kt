package com.artemchep.keyguard.util

import java.io.InputStream

/**
 * Reads up to a specified number of bytes from the input stream. This
 * method blocks until the requested number of bytes has been read, end
 * of stream is detected, or an exception is thrown. This method does not
 * close the input stream.
 *
 * <p> The length of the returned array equals the number of bytes read
 * from the stream. If {@code len} is zero, then no bytes are read and
 * an empty byte array is returned. Otherwise, up to {@code len} bytes
 * are read from the stream. Fewer than {@code len} bytes may be read if
 * end of stream is encountered.
 *
 * <p> When this stream reaches end of stream, further invocations of this
 * method will return an empty byte array.
 *
 * <p> Note that this method is intended for simple cases where it is
 * convenient to read the specified number of bytes into a byte array. The
 * total amount of memory allocated by this method is proportional to the
 * number of bytes read from the stream which is bounded by {@code len}.
 * Therefore, the method may be safely called with very large values of
 * {@code len} provided sufficient memory is available.
 *
 * <p> The behavior for the case where the input stream is <i>asynchronously
 * closed</i>, or the thread interrupted during the read, is highly input
 * stream specific, and therefore not specified.
 *
 * <p> If an I/O error occurs reading from the input stream, then it may do
 * so after some, but not all, bytes have been read. Consequently the input
 * stream may not be at end of stream and may be in an inconsistent state.
 * It is strongly recommended that the stream be promptly closed if an I/O
 * error occurs.
 */
internal fun InputStream.readNBytesCompat(
    count: Int,
): ByteArray {
    require(count >= 0) {
        "count must be non-negative"
    }
    if (count == 0) {
        return byteArrayOf()
    }

    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(buffer, offset, count - offset)
        if (read < 0) {
            break
        }
        if (read == 0) {
            val next = read()
            if (next < 0) {
                break
            }
            buffer[offset] = next.toByte()
            offset += 1
            continue
        }
        offset += read
    }

    return if (offset == count) {
        buffer
    } else {
        buffer.copyOf(offset)
    }
}
