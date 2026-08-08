package com.artemchep.keyguard.util.io

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * A source that tees every byte read from [input] into [output] and
 * counts them in [size]. The transfer buffer is erased after each read
 * so no payload bytes linger in memory.
 */
class CopyingRawSource(
    private val input: Source,
    private val output: Sink,
) : RawSource {
    private val transfer = ByteArray(STREAM_COPY_BUFFER_BYTES)
    private var closed = false

    var size: Long = 0L
        private set

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Copying source is closed." }
        require(byteCount >= 0L) { "Invalid copying source read size." }
        if (byteCount == 0L) {
            return 0L
        }
        val requested = minOf(byteCount, transfer.size.toLong()).toInt()
        val read = input.readAtMostTo(
            transfer,
            startIndex = 0,
            endIndex = requested,
        )
        if (read > 0) {
            try {
                output.write(transfer, startIndex = 0, endIndex = read)
                sink.write(transfer, startIndex = 0, endIndex = read)
                check(size <= Long.MAX_VALUE - read) { "Copying source size overflow." }
                size += read
            } finally {
                transfer.fill(0, fromIndex = 0, toIndex = read)
            }
        }
        return read.toLong()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            input.close()
        } finally {
            transfer.fill(0)
        }
    }

    private companion object {
        const val STREAM_COPY_BUFFER_BYTES = 64 * 1024
    }
}
