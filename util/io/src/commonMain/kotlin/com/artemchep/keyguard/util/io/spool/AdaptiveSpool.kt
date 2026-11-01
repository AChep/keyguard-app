package com.artemchep.keyguard.util.io.spool

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Stores a bounded byte stream in erasable memory and lazily migrates it to a spill
 * [ByteStoreWriter] once [memoryLimitBytes] is exceeded.
 *
 * The writable [sink] may be claimed only once, and [seal] transfers ownership of the buffered
 * bytes to the returned [ByteSnapshot]. Closing an unsealed spool discards any bytes still
 * buffered in the sink instead of flushing them to the spill.
 *
 * This class is intentionally storage-agnostic. A spill may be backed by a file, encrypted
 * storage, or any other replayable byte store. Instances are not thread-safe.
 *
 * State transitions and byte erasure must run for arbitrary failures from caller-provided
 * sources, sinks, and factories, including non-[Exception] throwables.
 */
@Suppress("TooGenericExceptionCaught")
class AdaptiveSpool(
    private val memoryLimitBytes: Long,
    private val maximumBytes: Long,
    private val spillFactory: ByteStoreFactory,
    private val checkCancellation: () -> Unit = {},
    private val limitExceeded: (maximumBytes: Long) -> Throwable = { limit ->
        IOException("Spool exceeds the supported limit of $limit bytes")
    },
) : ByteStoreWriter {
    init {
        require(memoryLimitBytes >= 0L) { "Memory spool limit must not be negative" }
        require(maximumBytes >= memoryLimitBytes) {
            "Maximum spool size must be greater than or equal to the memory limit"
        }
    }

    private var chunks = mutableListOf<ByteArray>()
    private val transferBuffer = ByteArray(TRANSFER_BUFFER_BYTES)
    private var spillWriter: ByteStoreWriter? = null
    private var spillSink: Sink? = null
    private var sizeBytes = 0L
    private var sinkClaimed = false
    private var inputClosed = false
    private var sealed = false
    private var failed = false
    private var discarding = false
    private var closed = false

    private val rawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            if (discarding) {
                source.skip(byteCount)
                return
            }
            checkWritable()
            require(byteCount >= 0L && byteCount <= source.size) {
                "Invalid adaptive spool write size"
            }
            if (byteCount > maximumBytes - sizeBytes) {
                failed = true
                throw limitExceeded(maximumBytes)
            }
            if (byteCount == 0L) return

            if (spillWriter == null && byteCount > memoryLimitBytes - sizeBytes) {
                migrateToSpill()
            }

            try {
                if (spillWriter == null) {
                    writeToMemory(source, byteCount)
                } else {
                    writeToSpill(source, byteCount)
                }
            } catch (e: Throwable) {
                failed = true
                throw e
            }
            sizeBytes += byteCount
        }

        override fun flush() {
            if (discarding) return
            spillSink?.flush()
        }

        override fun close() {
            if (inputClosed) return
            inputClosed = true
            if (discarding) {
                // Abandon path: the spill writer's close() owns the sink
                // cleanup and discards anything still buffered in it.
                return
            }
            spillSink?.close()
        }
    }
    private val inputSink: Sink = rawSink.buffered()

    val size: Long
        get() = sizeBytes

    val spilled: Boolean
        get() = spillWriter != null

    override fun sink(): Sink {
        check(!closed) { "Adaptive spool is closed" }
        check(!sealed) { "Adaptive spool is sealed" }
        check(!failed) { "Adaptive spool has failed" }
        check(!inputClosed) { "Adaptive spool input is closed" }
        check(!sinkClaimed) { "Adaptive spool sink has already been acquired" }
        sinkClaimed = true
        return inputSink
    }

    override fun seal(): ByteSnapshot {
        check(!closed) { "Adaptive spool is closed" }
        check(!sealed) { "Adaptive spool is already sealed" }
        check(!failed) { "Adaptive spool has failed" }
        try {
            inputSink.close()
            checkCancellation()
            val snapshot = spillWriter?.seal()
            val result = if (snapshot != null) {
                if (snapshot.size != sizeBytes) {
                    val error = IllegalStateException(
                        "Spill snapshot size does not match the adaptive spool size",
                    )
                    runCatching { snapshot.close() }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    throw error
                }
                snapshot
            } else {
                MemoryByteSnapshot(
                    chunks = chunks,
                    size = sizeBytes,
                ).also {
                    chunks = mutableListOf()
                }
            }
            sealed = true
            return result
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    override fun close() {
        if (closed) return
        // Bytes still buffered in the input sink are dropped, not flushed;
        // migrating them to a spill just to discard it would be wasted I/O,
        // and a failed spool would reject the flush anyway.
        discarding = true
        var failure: Throwable? = null
        try {
            if (!inputClosed) inputSink.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            spillWriter?.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        } finally {
            clearMemory()
            transferBuffer.fill(0)
            closed = true
        }
        failure?.let { throw it }
    }

    private fun checkWritable() {
        check(!closed) { "Adaptive spool is closed" }
        check(!sealed) { "Adaptive spool is sealed" }
        check(!failed) { "Adaptive spool has failed" }
        check(!inputClosed) { "Adaptive spool input is closed" }
        checkCancellation()
    }

    private fun migrateToSpill() {
        val writer = spillFactory.create()
        var sink: Sink? = null
        try {
            sink = writer.sink()
            chunks.forEach { chunk ->
                try {
                    checkCancellation()
                    sink.write(chunk)
                } finally {
                    chunk.fill(0)
                }
            }
            chunks.clear()
            spillWriter = writer
            spillSink = sink
        } catch (e: Throwable) {
            failed = true
            runCatching { sink?.close() }.exceptionOrNull()?.let(e::addSuppressed)
            runCatching { writer.close() }.exceptionOrNull()?.let(e::addSuppressed)
            clearMemory()
            throw e
        }
    }

    private fun writeToMemory(
        source: Buffer,
        byteCount: Long,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            checkCancellation()
            val requested = minOf(remaining, TRANSFER_BUFFER_BYTES.toLong()).toInt()
            val chunk = ByteArray(requested)
            var offset = 0
            try {
                while (offset < requested) {
                    val read = source.readAtMostTo(
                        chunk,
                        startIndex = offset,
                        endIndex = requested,
                    )
                    check(read > 0) { "Adaptive spool source ended early" }
                    offset += read
                }
            } catch (e: Throwable) {
                chunk.fill(0)
                throw e
            }
            chunks += chunk
            remaining -= requested
        }
    }

    private fun writeToSpill(
        source: Buffer,
        byteCount: Long,
    ) {
        val storage = checkNotNull(spillSink)
        var remaining = byteCount
        while (remaining > 0L) {
            checkCancellation()
            val requested = minOf(remaining, transferBuffer.size.toLong()).toInt()
            val read = source.readAtMostTo(
                transferBuffer,
                startIndex = 0,
                endIndex = requested,
            )
            check(read > 0) { "Adaptive spool source ended early" }
            try {
                storage.write(transferBuffer, startIndex = 0, endIndex = read)
            } finally {
                transferBuffer.fill(0, fromIndex = 0, toIndex = read)
            }
            remaining -= read
        }
    }

    private fun clearMemory() {
        chunks.forEach { chunk -> chunk.fill(0) }
        chunks.clear()
    }

    private companion object {
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
    }
}

private class MemoryByteSnapshot(
    private val chunks: List<ByteArray>,
    override val size: Long,
) : RandomAccessByteSnapshot {
    private var closed = false

    /** Logical start of every chunk; writes produce chunks of varying sizes. */
    private val chunkStarts = LongArray(chunks.size).also { starts ->
        var start = 0L
        chunks.forEachIndexed { index, chunk ->
            starts[index] = start
            start += chunk.size
        }
    }

    /** The last chunk starting at or before [position]; chunks are never empty. */
    private fun chunkContaining(position: Long): Int {
        val index = chunkStarts.asList().binarySearch(position)
        // A miss returns -(insertion point) - 1; the chunk before the insertion point holds it.
        return if (index >= 0) index else -index - 2
    }

    override fun openSource(): Source {
        check(!closed) { "Memory byte snapshot is closed" }
        return ChunkedMemoryRawSource(
            chunks = chunks,
            checkOpen = { check(!closed) { "Memory byte snapshot is closed" } },
        ).buffered()
    }

    override fun openReader(): ByteSnapshotReader {
        check(!closed) { "Memory byte snapshot is closed" }
        return object : ByteSnapshotReader {
            private var readerClosed = false

            override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
                check(!readerClosed) { "Memory byte snapshot reader is closed" }
                check(!closed) { "Memory byte snapshot is closed" }
                requireSnapshotReadRange(position, data, startIndex, endIndex)
                if (startIndex == endIndex || position >= size) return 0
                var chunkIndex = chunkContaining(position)
                var chunkOffset = (position - chunkStarts[chunkIndex]).toInt()
                var cursor = startIndex
                while (cursor < endIndex && chunkIndex < chunks.size) {
                    val chunk = chunks[chunkIndex]
                    val length = minOf(endIndex - cursor, chunk.size - chunkOffset)
                    chunk.copyInto(data, cursor, chunkOffset, chunkOffset + length)
                    cursor += length
                    chunkIndex += 1
                    chunkOffset = 0
                }
                return cursor - startIndex
            }

            override fun close() {
                readerClosed = true
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        chunks.forEach { chunk -> chunk.fill(0) }
    }
}

private class ChunkedMemoryRawSource(
    private val chunks: List<ByteArray>,
    private val checkOpen: () -> Unit,
) : kotlinx.io.RawSource {
    private var chunkIndex = 0
    private var chunkOffset = 0
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Memory byte snapshot source is closed" }
        checkOpen()
        require(byteCount >= 0L) { "Invalid memory snapshot read size" }
        if (byteCount == 0L || chunkIndex >= chunks.size) {
            return if (byteCount == 0L) 0L else -1L
        }

        var remaining = byteCount
        var written = 0L
        while (remaining > 0L && chunkIndex < chunks.size) {
            val chunk = chunks[chunkIndex]
            val length = minOf(remaining, (chunk.size - chunkOffset).toLong()).toInt()
            sink.write(
                chunk,
                startIndex = chunkOffset,
                endIndex = chunkOffset + length,
            )
            chunkOffset += length
            remaining -= length
            written += length
            if (chunkOffset == chunk.size) {
                chunkIndex += 1
                chunkOffset = 0
            }
        }
        return written
    }

    override fun close() {
        closed = true
    }
}
