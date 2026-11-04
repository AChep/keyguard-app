package com.artemchep.keyguard.util.age

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.readTo

/**
 * Writes an ordinary ZIP directly into an age password-encrypted stream.
 * No plaintext archive is staged. [finish] is required before publishing the result;
 * [close] only abandons the session and never finishes a partial export.
 */
@Suppress("TooGenericExceptionCaught") // Construction must release native handles on every failure.
class AgeZipWriter(
    private val output: Sink,
    secret: AgeSecret,
) : AutoCloseable {
    private val session = AgeSession()
    private val chunk = ByteArray(AGE_CHUNK_BYTES)
    private var finished = false

    init {
        var bytes: ByteArray? = null
        try {
            bytes = secret.encode()
            session.call(secret.createWriterCommand, bytes)
            drain()
        } catch (e: Throwable) {
            session.close()
            throw e
        } finally {
            bytes?.fill(0)
        }
    }

    suspend fun entry(name: String, write: suspend (Sink) -> Unit) {
        check(!finished) { "Age archive is finished" }
        val context = currentCoroutineContext()
        context.ensureActive()
        session.call(AgeCommand.BEGIN_ENTRY, name.encodeToByteArray(throwOnInvalidSequence = true))
        drain()
        var active = true
        val sink = object : RawSink {
            // A buffered sink emits one segment at a time; collect whole chunks.
            private val pending = Buffer()

            override fun write(source: Buffer, byteCount: Long) {
                check(active) { "Archive entry is closed" }
                require(byteCount >= 0L)
                pending.write(source, byteCount)
                while (pending.size >= AGE_CHUNK_BYTES) send(AGE_CHUNK_BYTES)
            }

            override fun flush() {
                while (pending.size > 0L) send(minOf(pending.size, AGE_CHUNK_BYTES.toLong()).toInt())
            }

            override fun close() = pending.clear()

            private fun send(count: Int) {
                context.ensureActive()
                pending.readTo(chunk, 0, count)
                try { session.call(AgeCommand.WRITE, chunk, count) } finally { chunk.fill(0, 0, count) }
                drain()
            }
        }.buffered()
        try {
            write(sink)
            sink.flush()
            context.ensureActive()
            session.call(AgeCommand.END_ENTRY)
            drain()
        } finally { active = false }
    }

    fun finish() {
        check(!finished) { "Age archive is finished" }
        session.call(AgeCommand.FINISH)
        drain()
        output.flush()
        finished = true
    }

    private fun drain() {
        while (true) {
            val bytes = session.call(AgeCommand.DRAIN)
            if (bytes.isEmpty()) return
            try { output.write(bytes) } finally { bytes.fill(0) }
        }
    }

    override fun close() = session.close()
}
