package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.foundation.io.ByteSnapshot
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

internal fun ByteSnapshot.readBytes(): ByteArray = openSource().use { source ->
    source.readByteArray()
}

internal class TestPrivateTemporaryStorage(
    private val tamperOnFirstSource: ((ByteArray) -> ByteArray)? = null,
    private val sinkOpenFailure: Throwable? = null,
    private val sinkCloseFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
) : PrivateTemporaryStorage {
    private var bytes = ByteArray(0)
    private var sourceCount = 0
    private var sinkClaimed = false
    private var sinkClosed = false
    private var sealed = false
    private var closed = false

    var sinkCloseCount = 0
        private set

    var closeCount = 0
        private set

    val storedByteCount: Int
        get() = bytes.size

    private val writableSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            check(!sinkClosed)
            val chunk = source.readByteArray(byteCount.toInt())
            try {
                bytes += chunk
            } finally {
                chunk.fill(0)
            }
        }

        override fun flush() = Unit

        override fun close() {
            if (sinkClosed) return
            sinkClosed = true
            sinkCloseCount++
            sinkCloseFailure?.let { throw it }
        }
    }

    override fun sink(): RawSink {
        sinkOpenFailure?.let { throw it }
        check(!closed)
        check(!sealed)
        check(!sinkClaimed)
        sinkClaimed = true
        return writableSink
    }

    override fun sealForReading() {
        check(!closed)
        check(!sealed)
        writableSink.close()
        sealed = true
    }

    override fun source(): RawSource {
        check(!closed)
        check(sealed)
        if (sourceCount++ == 0) {
            tamperOnFirstSource?.let { transform -> bytes = transform(bytes) }
        }
        return Buffer().apply { write(bytes) }
    }

    fun storedBytes(): ByteArray = bytes.copyOf()

    override fun close() {
        if (closed) return
        closed = true
        writableSink.close()
        closeCount++
        closeFailure?.let { throw it }
    }
}
