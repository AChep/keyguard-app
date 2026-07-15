package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered

/**
 * Stores a bounded byte stream in erasable memory and lazily migrates it to [SpillStorage] once
 * [memoryLimitBytes] is exceeded.
 *
 * This class is intentionally storage-agnostic. A spill may be backed by a file, encrypted
 * storage, or any other replayable byte store. Instances are not thread-safe.
 */
class AdaptiveSpool(
    private val memoryLimitBytes: Long,
    private val maximumBytes: Long,
    private val spillFactory: () -> SpillStorage,
    private val limitExceeded: (maximumBytes: Long) -> Throwable = { limit ->
        IOException("Spool exceeds the supported limit of $limit bytes")
    },
) : AutoCloseable {
    init {
        require(memoryLimitBytes >= 0L) { "Memory spool limit must not be negative" }
        require(maximumBytes >= memoryLimitBytes) {
            "Maximum spool size must be greater than or equal to the memory limit"
        }
    }

    private val chunks = mutableListOf<ByteArray>()
    private val transferBuffer = ByteArray(TRANSFER_BUFFER_BYTES)
    private var spill: SpillStorage? = null
    private var sizeBytes = 0L
    private var inputClosed = false
    private var sealed = false
    private var replayed = false
    private var closed = false

    private val rawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            checkWritable()
            require(byteCount >= 0L && byteCount <= source.size) {
                "Invalid adaptive spool write size"
            }
            if (byteCount > maximumBytes - sizeBytes) {
                throw limitExceeded(maximumBytes)
            }
            if (byteCount == 0L) return

            if (spill == null && byteCount > memoryLimitBytes - sizeBytes) {
                migrateToSpill()
            }

            if (spill == null) {
                writeToMemory(source, byteCount)
            } else {
                writeToSpill(source, byteCount)
            }
            sizeBytes += byteCount
        }

        override fun flush() = Unit

        override fun close() {
            inputClosed = true
        }
    }
    private val inputSink: Sink = rawSink.buffered()

    val size: Long
        get() = sizeBytes

    val spilled: Boolean
        get() = spill != null

    fun sink(): Sink {
        check(!closed) { "Adaptive spool is closed" }
        check(!sealed) { "Adaptive spool is sealed" }
        check(!inputClosed) { "Adaptive spool input is closed" }
        return inputSink
    }

    fun seal() {
        check(!closed) { "Adaptive spool is closed" }
        check(!sealed) { "Adaptive spool is already sealed" }
        inputSink.close()
        spill?.seal()
        sealed = true
    }

    fun replayTo(output: Sink) {
        check(!closed) { "Adaptive spool is closed" }
        check(sealed) { "Adaptive spool must be sealed before replay" }
        check(!replayed) { "Adaptive spool has already been replayed" }
        replayed = true
        try {
            val storage = spill
            if (storage != null) {
                storage.replayTo(output)
            } else {
                chunks.forEach { chunk -> output.write(chunk) }
            }
        } finally {
            clearMemory()
        }
    }

    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        try {
            if (!inputClosed) inputSink.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            spill?.close()
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
        check(!inputClosed) { "Adaptive spool input is closed" }
    }

    private fun migrateToSpill() {
        val storage = spillFactory()
        spill = storage
        try {
            chunks.forEach { chunk ->
                try {
                    storage.write(chunk, startIndex = 0, endIndex = chunk.size)
                } finally {
                    chunk.fill(0)
                }
            }
            chunks.clear()
        } catch (e: Throwable) {
            runCatching { storage.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    private fun writeToMemory(
        source: Buffer,
        byteCount: Long,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
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
        val storage = checkNotNull(spill)
        var remaining = byteCount
        while (remaining > 0L) {
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

/** Replayable storage used after an [AdaptiveSpool] exceeds its memory threshold. */
interface SpillStorage : AutoCloseable {
    fun write(
        source: ByteArray,
        startIndex: Int,
        endIndex: Int,
    )

    fun seal()

    fun replayTo(output: Sink)
}
