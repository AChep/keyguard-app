package com.artemchep.keyguard.util.zip

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Reads an entry of [upstream], throwing [ZipArchiveException] once it yields
 * more than [maxBytes] ([ZipArchiveError.TooLarge]), or a size other than
 * the [expectedSize] the archive lists, if any ([ZipArchiveError.Invalid]):
 * the size an archive lists for an entry is the archive's word, not a bound.
 */
internal class ZipLimitedSource(
    private val upstream: RawSource,
    private val maxBytes: Long,
    private val expectedSize: Long? = null,
) : RawSource {
    private var total = 0L

    @Suppress("ThrowsCount") // Verify both advertised size and the caller's independent limit.
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val remaining = minOf(maxBytes, expectedSize ?: Long.MAX_VALUE) - total
        // One byte past the limit is enough to tell that it is exceeded.
        val limit = if (remaining == Long.MAX_VALUE) remaining else remaining + 1
        val read = upstream.readAtMostTo(sink, minOf(byteCount, limit))
        if (read == -1L) {
            if (expectedSize != null && total != expectedSize) throw ZipArchiveException(ZipArchiveError.Invalid)
        } else {
            total += read
            if (total > maxBytes) throw ZipArchiveException(ZipArchiveError.TooLarge)
            if (expectedSize != null && total > expectedSize) throw ZipArchiveException(ZipArchiveError.Invalid)
        }
        return read
    }

    override fun close() = upstream.close()
}
