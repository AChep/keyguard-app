package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.spool.AdaptiveSpool
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered

/**
 * Adapts pathless [PrivateTemporaryStorage] to the [ByteStoreWriter]
 * contract, making scratch storage usable as an [AdaptiveSpool] spill target.
 *
 * The storage adds no encryption layer of its own: bytes land on disk as
 * written, protected only by the scratch storage's owner-only, pathless
 * nature. Callers spooling plaintext secrets should wrap this in an
 * encrypting writer instead of using it directly.
 *
 * Close catches every throwable so both owned resources get a cleanup attempt,
 * then rethrows the primary failure with any cleanup failure suppressed.
 */
@InternalKeyguardIoApi
@Suppress("TooGenericExceptionCaught")
class PrivateTemporarySpillStorage(
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
