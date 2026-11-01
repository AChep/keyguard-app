package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered

/**
 * The lifecycle shared by writers that encrypt provisional plaintext into
 * private temporary storage: one sink, one seal, and no further use after
 * any failure.
 *
 * The writer owns [storage] and the subclass's key material. A successful
 * [seal] transfers both to the snapshot; closing an unsealed writer closes
 * the storage and releases the key material.
 */
@OptIn(InternalKeyguardIoApi::class)
internal abstract class EncryptingSpillWriter<S : ByteSnapshot>(
    protected val storage: PrivateTemporaryStorage,
    protected val checkCancellation: () -> Unit,
) : ByteStoreWriter {
    protected val ciphertextSink: Sink = storage.sink().buffered()
    private val transfer = ByteArray(TRANSFER_BYTES)
    private var sinkClaimed = false
    private var inputClosed = false
    private var sealed = false
    private var failed = false
    private var closed = false

    private val plaintextRawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            if (closed) {
                source.skip(byteCount)
                return
            }
            checkWritable()
            require(byteCount >= 0L && byteCount <= source.size) {
                "Invalid encrypted spill write size"
            }
            writeOrFail {
                checkCancellation()
                var remaining = byteCount
                while (remaining > 0L) {
                    checkCancellation()
                    val requested = minOf(remaining, transfer.size.toLong()).toInt()
                    val read = source.readAtMostTo(transfer, 0, requested)
                    check(read > 0) { "Encrypted spill source ended early" }
                    try {
                        absorb(transfer, read)
                    } finally {
                        transfer.fill(0, 0, read)
                    }
                    remaining -= read
                }
            }
        }

        override fun flush() {
            if (closed) return
            checkWritable()
            writeOrFail {
                checkCancellation()
                ciphertextSink.flush()
            }
        }

        override fun close() {
            inputClosed = true
        }
    }
    private val plaintextSink = plaintextRawSink.buffered()

    /** Encrypts `bytes[0, length)`; the caller zeroes them afterwards. */
    protected abstract fun absorb(bytes: ByteArray, length: Int)

    /**
     * Encrypts any plaintext still held, seals [storage] and returns the
     * snapshot that takes ownership of the storage and the key material.
     */
    protected abstract fun finishSeal(): S

    /** Discards in-progress encryption state; runs on every close. */
    protected abstract fun closeEncryptor()

    /** Zeroes the key material of an unsealed writer, after its storage is closed. */
    protected abstract fun releaseSecrets()

    override fun sink(): Sink {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is sealed" }
        check(!failed) { "Encrypted spill storage has failed" }
        check(!sinkClaimed) { "Encrypted spill storage sink has already been acquired" }
        sinkClaimed = true
        return plaintextSink
    }

    override fun seal(): S {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is already sealed" }
        return writeOrFail {
            plaintextSink.close()
            checkCancellation()
            finishSeal().also {
                // Successful sealing transfers the storage and keys to the snapshot.
                sealed = true
            }
        }
    }

    // Every owned resource gets a cleanup attempt; the primary failure is rethrown.
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (closed) return
        // Plaintext still buffered in the sink is discarded, not flushed: the
        // abandon path must not fail on it, and its ciphertext could only
        // reach storage which is about to be deleted.
        closed = true
        transfer.fill(0)
        var failure: Throwable? = null
        fun record(e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        try {
            plaintextSink.close()
        } catch (e: Throwable) {
            record(e)
        }
        try {
            closeEncryptor()
        } catch (e: Throwable) {
            record(e)
        }
        try {
            ciphertextSink.close()
        } catch (e: Throwable) {
            record(e)
        }
        if (!sealed) {
            try {
                storage.close()
            } catch (e: Throwable) {
                record(e)
            } finally {
                releaseSecrets()
            }
        }
        failure?.let { throw it }
    }

    private fun checkWritable() {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is sealed" }
        check(!inputClosed) { "Encrypted spill storage input is closed" }
    }

    // Any failure may leave partial ciphertext or erased plaintext, so the writer
    // must remain unusable even if a caller catches the failure and tries again.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> writeOrFail(block: () -> T): T {
        check(!failed) { "Encrypted spill storage has failed" }
        return try {
            block()
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    companion object {
        private const val TRANSFER_BYTES = 64 * 1024

        /**
         * Builds a writer that owns [storage] from the start. If [build] fails,
         * the wipes it registered run in order and [storage] is closed.
         */
        @Suppress("TooGenericExceptionCaught")
        inline fun <W : EncryptingSpillWriter<*>> createOwning(
            storage: PrivateTemporaryStorage,
            checkCancellation: () -> Unit,
            build: (wipeOnFailure: MutableList<() -> Unit>) -> W,
        ): W {
            val wipeOnFailure = mutableListOf<() -> Unit>()
            try {
                checkCancellation()
                return build(wipeOnFailure)
            } catch (failure: Throwable) {
                wipeOnFailure.forEach { it() }
                runCatching { storage.close() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
