package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.foundation.io.SpillStorage
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
) : SpillStorage {
    private val storageSink = storage.sink().buffered()
    private var sealed = false
    private var replayed = false
    private var closed = false

    override fun write(
        source: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ) {
        checkWritable()
        require(startIndex in 0..endIndex && endIndex <= source.size) {
            "Invalid private spill write range"
        }
        storageSink.write(source, startIndex, endIndex)
    }

    override fun seal() {
        check(!closed) { "Private spill storage is closed" }
        check(!sealed) { "Private spill storage is already sealed" }
        storageSink.close()
        storage.sealForReading()
        sealed = true
    }

    override fun replayTo(output: Sink) {
        check(!closed) { "Private spill storage is closed" }
        check(sealed) { "Private spill storage must be sealed before replay" }
        check(!replayed) { "Private spill storage has already been replayed" }
        replayed = true

        storage.rewind()
        storage.source().buffered().use { source ->
            source.consumeWithErasedBuffer { buffer, length ->
                output.write(buffer, 0, length)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            storageSink.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            storage.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
    }

    private fun checkWritable() {
        check(!closed) { "Private spill storage is closed" }
        check(!sealed) { "Private spill storage is sealed" }
    }
}
