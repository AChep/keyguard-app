package app.keemobile.kotpass.cryptography.format

import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

internal class CipherSource(
    private val upstream: Source,
    private val session: CipherSession,
) : Source {
    private val output = Buffer()
    private var finished = false
    private var sessionClosed = false
    private var closed = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Cipher source is closed" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        while (output.size == 0L && !finished) {
            val input = Buffer()
            val read = upstream.read(input, NATIVE_CRYPTO_STREAM_CHUNK_BYTES.toLong())
            if (read == -1L) {
                finishSession()
            } else if (read > 0L) {
                val data = input.readByteArray()
                val transformed =
                    try {
                        session.update(data)
                    } finally {
                        data.fill(0)
                    }
                try {
                    output.write(transformed)
                } finally {
                    transformed.fill(0)
                }
            }
        }

        return if (output.size > 0L) {
            output.read(sink, minOf(byteCount, output.size))
        } else {
            -1L
        }
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        if (!sessionClosed) {
            try {
                session.close()
            } catch (error: Throwable) {
                failure = error
            } finally {
                sessionClosed = true
            }
        }
        try {
            upstream.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        } finally {
            output.clear()
        }
        failure?.let { throw it }
    }

    private fun finishSession() {
        finished = true
        var failure: Throwable? = null
        try {
            val finalOutput = session.finish()
            try {
                output.write(finalOutput)
            } finally {
                finalOutput.fill(0)
            }
        } catch (error: Throwable) {
            failure = error
        }
        try {
            session.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        } finally {
            sessionClosed = true
        }
        failure?.let { throw it }
    }
}

internal class CipherSink(
    private val downstream: Sink,
    private val session: CipherSession,
) : Sink {
    private var finished = false
    private var sessionClosed = false
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        checkWritable()
        require(byteCount >= 0L && byteCount <= source.size) {
            "Invalid cipher write size: $byteCount"
        }
        var remaining = byteCount
        while (remaining > 0L) {
            val length = minOf(remaining, NATIVE_CRYPTO_STREAM_CHUNK_BYTES.toLong()).toInt()
            val data = source.readByteArray(length.toLong())
            val transformed =
                try {
                    session.update(data)
                } finally {
                    data.fill(0)
                }
            try {
                downstream.write(Buffer().write(transformed), transformed.size.toLong())
            } finally {
                transformed.fill(0)
            }
            remaining -= length
        }
    }

    override fun flush() {
        check(!closed) { "Cipher sink is closed" }
        downstream.flush()
    }

    override fun timeout(): Timeout = downstream.timeout()

    fun finish() {
        checkWritable()
        finished = true
        var failure: Throwable? = null
        try {
            val finalOutput = session.finish()
            try {
                downstream.write(Buffer().write(finalOutput), finalOutput.size.toLong())
            } finally {
                finalOutput.fill(0)
            }
        } catch (error: Throwable) {
            failure = error
        }
        try {
            session.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        } finally {
            sessionClosed = true
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!sessionClosed) {
            sessionClosed = true
            session.close()
        }
    }

    private fun checkWritable() {
        check(!closed) { "Cipher sink is closed" }
        check(!finished) { "Cipher sink is already finished" }
    }
}
