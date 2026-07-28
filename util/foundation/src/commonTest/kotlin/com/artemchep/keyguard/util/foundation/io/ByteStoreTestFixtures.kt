package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.readByteArray

internal fun ByteSnapshot.readBytes(): ByteArray = openSource().use { source ->
    source.readByteArray()
}

internal class FakeByteStoreWriter : ByteStoreWriter {
    private val buffer = Buffer()
    private var sinkClaimed = false
    private var ownershipTransferred = false
    var sealed = false
        private set
    var closed = false
        private set
    var snapshotClosed = false
        private set

    override fun sink(): Sink {
        check(!sinkClaimed)
        check(!sealed)
        check(!closed)
        sinkClaimed = true
        return buffer
    }

    override fun seal(): ByteSnapshot {
        check(!sealed)
        check(!closed)
        sealed = true
        ownershipTransferred = true
        val bytes = buffer.readByteArray()
        return object : ByteSnapshot {
            private var closed = false

            override val size: Long = bytes.size.toLong()

            override fun openSource() = Buffer().apply {
                check(!closed)
                write(bytes)
            }

            override fun close() {
                if (closed) return
                closed = true
                snapshotClosed = true
                bytes.fill(0)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!ownershipTransferred) {
            buffer.readByteArray().fill(0)
        }
    }
}

internal class RecordingRawSink : RawSink {
    val data = Buffer()
    var flushed = false
        private set
    var closed = false
        private set

    override fun write(source: Buffer, byteCount: Long) {
        data.write(source, byteCount)
    }

    override fun flush() {
        flushed = true
    }

    override fun close() {
        closed = true
    }
}

internal class FakeIoFailure : RuntimeException()

internal class FailingRawSink : RawSink {
    override fun write(source: Buffer, byteCount: Long) = throw FakeIoFailure()

    override fun flush() = Unit

    override fun close() = Unit
}
