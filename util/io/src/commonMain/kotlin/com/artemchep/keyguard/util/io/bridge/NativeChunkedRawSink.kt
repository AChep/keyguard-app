package com.artemchep.keyguard.util.io.bridge

import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Byte-shuttle chunk size between kotlinx-io buffers and the native core.
 *
 * Measured through the full JNI + Durable-protocol stack on an Apple-silicon
 * desktop (128 MiB payload): 64 KiB ≈ 826 MB/s, 256 KiB ≈ 948 MB/s; beyond
 * that the boundary cost is already amortized into noise.
 */
internal const val NATIVE_IO_CHUNK_SIZE: Int = 256 * 1024

/**
 * A [RawSink] that shuttles bytes to the native core through one reusable
 * chunk.
 *
 * Upstream buffered sinks emit small (~8 KiB) segments, so the chunk
 * accumulates them and crosses the native boundary only when full, on
 * [flush], or on [close]. After every native call the used prefix is
 * zero-filled so plaintext does not linger on the heap.
 */
@Suppress("TooGenericExceptionCaught")
internal class NativeChunkedRawSink(
    private val writeChunk: (chunk: ByteArray, length: Int) -> Unit,
) : RawSink {
    private val chunk = ByteArray(NATIVE_IO_CHUNK_SIZE)
    private var used = 0
    private var closed = false
    private var discarding = false
    private var failure: Throwable? = null

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Sink is closed" }
        require(byteCount >= 0L && byteCount <= source.size) {
            "Invalid sink write size"
        }
        if (discarding) {
            discardFrom(source, byteCount)
            return
        }
        check(failure == null) { "Sink has failed" }
        var remaining = byteCount
        while (remaining > 0L) {
            if (used == chunk.size) {
                emitChunkOrDiscardRemaining(
                    source = source,
                    remaining = remaining,
                )
            }
            val step = minOf(remaining, (chunk.size - used).toLong()).toInt()
            val read = source.readAtMostTo(chunk, used, used + step)
            check(read > 0) { "Buffer exhausted before byteCount" }
            used += read
            remaining -= read
        }
    }

    override fun flush() {
        if (discarding) {
            check(!closed) { "Sink is closed" }
            return
        }
        checkUsable()
        emitChunk()
    }

    override fun close() {
        if (!closed) {
            if (discarding || failure != null) {
                discard()
            } else {
                try {
                    // kotlinx-io buffered sinks hand their remaining bytes to
                    // the raw sink on close without a final flush.
                    emitChunk()
                } finally {
                    closed = true
                    chunk.fill(0)
                }
            }
        }
    }

    /**
     * Erases pending bytes and accepts subsequent writes only to consume and
     * erase them. This lets an owning buffered sink drain and close without
     * emitting its private buffer to native storage.
     */
    internal fun beginDiscarding() {
        if (closed) return
        discarding = true
        used = 0
        chunk.fill(0)
    }

    /**
     * Closes and erases this sink without emitting its pending bytes.
     */
    internal fun discard() {
        closed = true
        used = 0
        chunk.fill(0)
    }

    private fun emitChunk() {
        if (used == 0) return
        val length = used
        try {
            writeChunk(chunk, length)
        } catch (e: Throwable) {
            failure = failure ?: e
            throw e
        } finally {
            used = 0
            chunk.fill(0, 0, length)
        }
    }

    private fun emitChunkOrDiscardRemaining(
        source: Buffer,
        remaining: Long,
    ) {
        try {
            emitChunk()
        } catch (e: Throwable) {
            discardFrom(source, remaining)
            throw e
        }
    }

    private fun discardFrom(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val step = minOf(remaining, chunk.size.toLong()).toInt()
            val read = source.readAtMostTo(chunk, 0, step)
            check(read > 0) { "Buffer exhausted before byteCount" }
            chunk.fill(0, 0, read)
            remaining -= read
        }
    }

    private fun checkUsable() {
        check(!closed) { "Sink is closed" }
        check(failure == null) { "Sink has failed" }
    }
}
