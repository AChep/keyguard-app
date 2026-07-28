package app.keemobile.kotpass.io

import app.keemobile.kotpass.errors.FormatError
import kotlinx.io.write
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

internal const val STREAM_BUFFER_SIZE: Int = 64 * 1024

internal class KotlinxSourceAdapter(
    private val source: kotlinx.io.Source,
) : Source {
    private val transfer = ByteArray(STREAM_BUFFER_SIZE)

    var readFailure: Throwable? = null
        private set

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        val requested = minOf(byteCount, transfer.size.toLong()).toInt()
        val read =
            try {
                source.readAtMostTo(transfer, startIndex = 0, endIndex = requested)
            } catch (error: Throwable) {
                if (readFailure == null) {
                    readFailure = error
                }
                throw error
            }
        if (read <= 0) return read.toLong()
        try {
            sink.write(transfer, 0, read)
        } finally {
            transfer.fill(0, fromIndex = 0, toIndex = read)
        }
        return read.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        transfer.fill(0)
    }
}

internal class KotlinxSinkAdapter(
    private val sink: kotlinx.io.Sink,
) : Sink {
    private val transfer = ByteArray(STREAM_BUFFER_SIZE)
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "Sink adapter is closed" }
        require(byteCount >= 0L && byteCount <= source.size) {
            "Invalid sink write size: $byteCount"
        }
        var remaining = byteCount
        while (remaining > 0L) {
            val requested = minOf(remaining, transfer.size.toLong()).toInt()
            val read = source.read(transfer, 0, requested)
            check(read > 0) { "Okio source ended early" }
            try {
                sink.write(transfer, startIndex = 0, endIndex = read)
            } finally {
                transfer.fill(0, fromIndex = 0, toIndex = read)
            }
            remaining -= read
        }
    }

    override fun flush() = sink.flush()

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
        transfer.fill(0)
    }
}

internal class NonClosingSink(
    private val delegate: Sink,
) : Sink {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) = delegate.write(source, byteCount)

    override fun flush() = delegate.flush()

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.flush()
}

internal inline fun <T> Source.closeOnFailure(block: () -> T): T =
    try {
        block()
    } catch (error: Throwable) {
        try {
            close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        throw error
    }

internal class LimitedSource(
    private val delegate: Source,
    private val maximumBytes: Long,
    private val limitExceeded: () -> Throwable = {
        FormatError.InvalidContent("Decoded content exceeds $maximumBytes bytes.")
    },
) : Source {
    private var bytesRead = 0L

    init {
        require(maximumBytes >= 0L) { "Maximum source size must not be negative" }
    }

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        val remaining = maximumBytes - bytesRead
        val sentinelLimit = if (remaining == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            remaining + 1L
        }
        val requested = minOf(byteCount, sentinelLimit)
        val read = delegate.read(sink, requested)
        if (read > 0L) {
            bytesRead += read
            if (bytesRead > maximumBytes) throw limitExceeded()
        }
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.close()
}
