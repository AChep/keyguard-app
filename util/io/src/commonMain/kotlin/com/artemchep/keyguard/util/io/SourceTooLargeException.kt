package com.artemchep.keyguard.util.io

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * A source yielded more than [maxBytes], the most its reader accepts.
 */
class SourceTooLargeException(
    val maxBytes: Long,
) : RuntimeException("The source exceeds $maxBytes bytes")

/**
 * Streams this source, throwing [SourceTooLargeException] once it yields
 * more than [maxBytes]. Closing it leaves this source open.
 */
fun Source.limitedTo(
    maxBytes: Long,
): Source {
    require(maxBytes >= 0L) { "Max bytes must not be negative" }
    return LimitedRawSource(this, maxBytes).buffered()
}

private class LimitedRawSource(
    private val upstream: Source,
    private val maxBytes: Long,
) : RawSource {
    private var total = 0L

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val remaining = maxBytes - total
        // One byte past the limit is enough to tell that it is exceeded.
        val limit = if (remaining == Long.MAX_VALUE) remaining else remaining + 1
        val read = upstream.readAtMostTo(sink, minOf(byteCount, limit))
        if (read > 0) {
            total += read
            if (total > maxBytes) {
                throw SourceTooLargeException(maxBytes)
            }
        }
        return read
    }

    override fun close() = Unit
}
