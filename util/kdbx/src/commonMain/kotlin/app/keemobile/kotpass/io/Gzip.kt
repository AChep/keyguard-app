package app.keemobile.kotpass.io

import app.keemobile.kotpass.errors.FormatError
import com.artemchep.keyguard.util.gzip.GzipError
import com.artemchep.keyguard.util.gzip.GzipException
import com.artemchep.keyguard.util.gzip.gunzip
import okio.GzipSink
import okio.GzipSource
import okio.Sink
import okio.Source

/**
 * Upper bound, in bytes, on the decompressed output. A KDBX body or
 * binary attachment that inflates beyond this is treated as a decompression
 * bomb and rejected instead of being allowed to exhaust the heap.
 */
internal const val MAX_DECOMPRESSED_SIZE: Long = 512L * 1024 * 1024

/**
 * Inflates the GZIP stream, refusing to
 * produce more than [MAX_DECOMPRESSED_SIZE] bytes.
 */
internal fun ByteArray.gunzipWithinLimit(): ByteArray = try {
    gunzip(maxSize = MAX_DECOMPRESSED_SIZE)
} catch (e: GzipException) {
    throw if (e.reason == GzipError.TooLarge) decompressedSizeExceeded(MAX_DECOMPRESSED_SIZE) else e
}

internal fun Source.gunzipSource(
    maxSize: Long = MAX_DECOMPRESSED_SIZE,
): Source = LimitedSource(
    delegate = GzipSource(this),
    maximumBytes = maxSize,
    limitExceeded = { decompressedSizeExceeded(maxSize) },
)

private fun decompressedSizeExceeded(maxSize: Long) = FormatError.FailedCompression(
    "Decompressed data exceeds the maximum allowed size ($maxSize bytes).",
)

/** Closing this gzip sink finishes the stream but leaves [this] open. */
internal fun Sink.gzipSink(): GzipSink = GzipSink(NonClosingSink(this))
