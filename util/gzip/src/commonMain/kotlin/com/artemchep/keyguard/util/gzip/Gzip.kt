package com.artemchep.keyguard.util.gzip

import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.IOException
import okio.buffer

private const val INFLATE_CHUNK_SIZE: Long = 64L * 1024

enum class GzipError { Invalid, TooLarge }

class GzipException(val reason: GzipError, cause: Throwable? = null) :
    Exception("Gzip operation failed: $reason", cause)

fun ByteArray.gzip(): ByteArray {
    val output = Buffer()
    val sink = GzipSink(output).buffer()
    try {
        sink.write(this)
    } finally {
        sink.close()
    }
    return output.readByteArray()
}

/**
 * Inflates the GZIP stream, refusing to
 * produce more than [maxSize] bytes.
 *
 * The stream must hold exactly one member: any bytes
 * after it, including another member, make it invalid.
 *
 * @throws GzipException if the stream is damaged or too large.
 */
fun ByteArray.gunzip(
    maxSize: Long,
    checkCancellation: () -> Unit = {},
): ByteArray {
    val output = Buffer()
    val source = GzipSource(Buffer().write(this))
    try {
        while (true) {
            checkCancellation()
            val read = source.readChunk(output)
            if (read == -1L) break
            if (output.size > maxSize) {
                output.clear()
                throw GzipException(GzipError.TooLarge)
            }
        }
    } finally {
        source.close()
    }
    return output.readByteArray()
}

@Suppress("TooGenericExceptionCaught") // Works around an okio bug, see below.
private fun GzipSource.readChunk(sink: Buffer): Long = try {
    read(sink, INFLATE_CHUNK_SIZE)
} catch (e: IOException) {
    throw GzipException(GzipError.Invalid, e)
} catch (e: NullPointerException) {
    // GzipSource throws this instead of an IOException when
    // the input ends right after an empty extra field.
    throw GzipException(GzipError.Invalid, e)
}
