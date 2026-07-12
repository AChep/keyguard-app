package app.keemobile.kotpass.io

import app.keemobile.kotpass.errors.FormatError
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * Upper bound, in bytes, on the output produced by [gunzip]. A KDBX body or
 * binary attachment that inflates beyond this is treated as a decompression
 * bomb and rejected instead of being allowed to exhaust the heap.
 */
internal const val MAX_DECOMPRESSED_SIZE: Long = 512L * 1024 * 1024

private const val INFLATE_CHUNK_SIZE: Long = 64L * 1024

internal fun ByteArray.gzip(): ByteArray {
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
 */
internal fun ByteArray.gunzip(
    maxSize: Long = MAX_DECOMPRESSED_SIZE
): ByteArray {
    val output = Buffer()
    val source = GzipSource(Buffer().write(this))
    try {
        while (true) {
            val read = source.read(output, INFLATE_CHUNK_SIZE)
            if (read == -1L) break
            if (output.size > maxSize) {
                throw FormatError.FailedCompression(
                    "Decompressed data exceeds the maximum allowed size ($maxSize bytes)."
                )
            }
        }
    } finally {
        source.close()
    }
    return output.readByteArray()
}
