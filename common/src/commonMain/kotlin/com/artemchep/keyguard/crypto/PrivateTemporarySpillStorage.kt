package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.foundation.io.ByteSnapshot
import com.artemchep.keyguard.util.foundation.io.ByteStoreWriter
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered

/**
 * Replayable owner-only storage for data which is already encrypted.
 *
 * Unlike [EncryptedTemporarySpillStorage], this storage does not add another encryption layer.
 * It is intended for authenticated file-format ciphertext, never plaintext.
 */
internal class PrivateTemporarySpillStorage(
    private val storage: PrivateTemporaryStorage,
) : ByteStoreWriter {
    private var sizeBytes = 0L
    private var sinkClaimed = false
    private var sealed = false
    private var ownershipTransferred = false
    private var discarding = false
    private var closed = false

    private var storageSink: Sink? = null

    override fun sink(): Sink {
        check(!closed) { "Private spill storage is closed" }
        check(!sealed) { "Private spill storage is sealed" }
        check(!sinkClaimed) { "Private spill storage sink has already been acquired" }
        sinkClaimed = true
        return CountingRawSink(storage.sink()).buffered().also { sink ->
            storageSink = sink
        }
    }

    override fun seal(): ByteSnapshot {
        check(!closed) { "Private spill storage is closed" }
        check(!sealed) { "Private spill storage is already sealed" }
        val sink = storageSink ?: sink()
        sink.close()
        storage.sealForReading()
        sealed = true
        ownershipTransferred = true
        return PrivateTemporaryByteSnapshot(
            storage = storage,
            size = sizeBytes,
        )
    }

    override fun close() {
        if (closed) return
        // Bytes still buffered in the sink are discarded, not flushed: they
        // could only reach a file which is about to be deleted.
        discarding = true
        closed = true
        var failure: Throwable? = null
        try {
            storageSink?.close()
        } catch (e: Throwable) {
            failure = e
        }
        if (!ownershipTransferred) {
            try {
                storage.close()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    private inner class CountingRawSink(
        private val delegate: RawSink,
    ) : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            if (discarding) {
                source.skip(byteCount)
                return
            }
            delegate.write(source, byteCount)
            sizeBytes += byteCount
        }

        override fun flush() {
            if (discarding) return
            delegate.flush()
        }

        override fun close() = delegate.close()
    }
}

private class PrivateTemporaryByteSnapshot(
    private val storage: PrivateTemporaryStorage,
    override val size: Long,
) : ByteSnapshot {
    private var closed = false

    override fun openSource() = run {
        check(!closed) { "Private temporary byte snapshot is closed" }
        storage.source().buffered()
    }

    override fun close() {
        if (closed) return
        closed = true
        storage.close()
    }
}
