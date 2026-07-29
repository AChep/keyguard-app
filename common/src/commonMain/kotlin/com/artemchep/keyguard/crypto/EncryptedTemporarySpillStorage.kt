package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import com.artemchep.keyguard.nativecrypto.NativeAesCbcPkcs7HmacSha256EncryptSession
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import com.artemchep.keyguard.util.io.consumeWithErasedBuffer
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Encrypts provisional plaintext before spilling it
 * into private temporary storage.
 */
internal class EncryptedTemporarySpillStorage private constructor(
    private val storage: PrivateTemporaryStorage,
    private val keys: FileEncryptionFormat.EncryptionKeys,
    private val iv: ByteArray,
) : ByteStoreWriter {
    private val ciphertextSink = storage.sink().buffered()
    private var encryptor: NativeAesCbcPkcs7HmacSha256EncryptSession? =
        NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Encryptor(
            encryptionKey = keys.encKey,
            macKey = keys.macKey,
            iv = iv,
        )
    private var plaintextBytes = 0L
    private var ciphertextBytes = 0L
    private var sinkClaimed = false
    private var inputClosed = false
    private var sealed = false
    private var ownershipTransferred = false
    private var discarding = false
    private var closed = false

    private val plaintextRawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            if (discarding) {
                source.skip(byteCount)
                return
            }
            checkWritable()
            require(byteCount >= 0L && byteCount <= source.size) {
                "Invalid encrypted spill write size"
            }

            val transfer = ByteArray(minOf(byteCount, STAGING_BUFFER_BYTES.toLong()).toInt())
            try {
                var remaining = byteCount
                while (remaining > 0L) {
                    val requested = minOf(remaining, transfer.size.toLong()).toInt()
                    val read = source.readAtMostTo(
                        transfer,
                        startIndex = 0,
                        endIndex = requested,
                    )
                    check(read > 0) { "Encrypted spill source ended early" }
                    try {
                        encrypt(transfer, length = read)
                    } finally {
                        transfer.fill(0, fromIndex = 0, toIndex = read)
                    }
                    remaining -= read
                }
            } finally {
                transfer.fill(0)
            }
        }

        override fun flush() {
            if (discarding) return
            ciphertextSink.flush()
        }

        override fun close() {
            inputClosed = true
        }
    }
    private val plaintextSink = plaintextRawSink.buffered()

    override fun sink(): Sink {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is sealed" }
        check(!sinkClaimed) { "Encrypted spill storage sink has already been acquired" }
        sinkClaimed = true
        return plaintextSink
    }

    override fun seal(): ByteSnapshot {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is already sealed" }
        val session = checkNotNull(encryptor)
        try {
            plaintextSink.close()
            val result = session.finish()
            val expectedMac = try {
                ciphertextSink.write(result.ciphertext)
                ciphertextBytes += result.ciphertext.size
                ciphertextSink.close()
                storage.sealForReading()
                result.mac.copyOf()
            } finally {
                result.ciphertext.fill(0)
                result.mac.fill(0)
            }
            sealed = true
            ownershipTransferred = true
            return EncryptedTemporaryByteSnapshot(
                storage = storage,
                keys = keys,
                iv = iv,
                expectedMac = expectedMac,
                ciphertextBytes = ciphertextBytes,
                size = plaintextBytes,
            )
        } finally {
            session.close()
            encryptor = null
        }
    }

    override fun close() {
        if (closed) return
        // Plaintext still buffered in the sink is discarded, not flushed: the
        // abandon path must not fail on it, and its ciphertext could only
        // reach a file which is about to be deleted.
        discarding = true
        closed = true
        var failure: Throwable? = null
        try {
            plaintextSink.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            encryptor?.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        try {
            ciphertextSink.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        if (!ownershipTransferred) {
            try {
                storage.close()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            } finally {
                with(NativeFileCrypto) { keys.clear() }
                iv.fill(0)
            }
        }
        failure?.let { throw it }
    }

    private fun encrypt(
        source: ByteArray,
        length: Int,
    ) {
        val session = checkNotNull(encryptor)
        var offset = 0
        while (offset < length) {
            val chunkLength = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, length - offset)
            val ciphertext = session.update(source, offset, chunkLength)
            try {
                ciphertextSink.write(ciphertext)
                ciphertextBytes += ciphertext.size
                plaintextBytes += chunkLength
            } finally {
                ciphertext.fill(0)
            }
            offset += chunkLength
        }
    }

    private fun checkWritable() {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is sealed" }
        check(!inputClosed) { "Encrypted spill storage input is closed" }
    }

    companion object {
        /** Takes ownership of [storage], including when initialization fails. */
        fun create(
            storage: PrivateTemporaryStorage,
        ): EncryptedTemporarySpillStorage = create(
            storage = storage,
            randomBytes = NativeCryptoPrimitives::randomBytes,
        )

        internal fun create(
            storage: PrivateTemporaryStorage,
            randomBytes: (Int) -> ByteArray,
        ): EncryptedTemporarySpillStorage {
            var keys: FileEncryptionFormat.EncryptionKeys? = null
            var iv: ByteArray? = null
            try {
                val keyMaterial = randomBytes(STAGING_KEY_BYTES)
                val createdKeys = try {
                    FileEncryptionFormat.requireAesCbc256HmacSha256Keys(keyMaterial)
                } finally {
                    keyMaterial.fill(0)
                }
                keys = createdKeys

                val createdIv = randomBytes(FileEncryptionFormat.IV_LENGTH)
                iv = createdIv

                return EncryptedTemporarySpillStorage(
                    storage = storage,
                    keys = createdKeys,
                    iv = createdIv,
                )
            } catch (failure: Throwable) {
                keys?.let { value ->
                    with(NativeFileCrypto) { value.clear() }
                }
                iv?.fill(0)
                runCatching { storage.close() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }

        const val STAGING_KEY_BYTES = 64
        const val STAGING_BUFFER_BYTES = 64 * 1024
    }
}

private class EncryptedTemporaryByteSnapshot(
    private val storage: PrivateTemporaryStorage,
    private val keys: FileEncryptionFormat.EncryptionKeys,
    private val iv: ByteArray,
    private val expectedMac: ByteArray,
    private val ciphertextBytes: Long,
    override val size: Long,
) : ByteSnapshot {
    private var closed = false

    override fun openSource(): Source {
        check(!closed) { "Encrypted temporary byte snapshot is closed" }
        authenticateCiphertext()
        val input = storage.source().buffered()
        try {
            val decryptor = NativeCryptoPrimitives.createAesCbcPkcs7Decryptor(
                key = keys.encKey,
                iv = iv,
            )
            return DecryptingRawSource(
                input = input,
                decryptor = decryptor,
                checkOpen = {
                    check(!closed) { "Encrypted temporary byte snapshot is closed" }
                },
            ).buffered()
        } catch (error: Throwable) {
            runCatching { input.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            storage.close()
        } catch (e: Throwable) {
            failure = e
        } finally {
            with(NativeFileCrypto) { keys.clear() }
            iv.fill(0)
            expectedMac.fill(0)
        }
        failure?.let { throw it }
    }

    private fun authenticateCiphertext() {
        val actual = NativeCryptoPrimitives.createHmacSha256(keys.macKey).use { hmac ->
            updateHmac(hmac, iv, iv.size)
            var bytesRead = 0L
            storage.source().buffered().use { source ->
                source.consumeWithErasedBuffer(
                    bufferSize = EncryptedTemporarySpillStorage.STAGING_BUFFER_BYTES,
                ) { data, length ->
                    updateHmac(hmac, data, length)
                    bytesRead += length
                }
            }
            if (bytesRead != ciphertextBytes) {
                throw IOException("Encrypted temporary spill has an unexpected size")
            }
            hmac.finish()
        }
        try {
            if (!actual.constantTimeEquals(expectedMac)) {
                throw IOException("Encrypted temporary spill authentication failed")
            }
        } finally {
            actual.fill(0)
        }
    }

    private fun updateHmac(
        session: NativeCryptoSession,
        data: ByteArray,
        length: Int,
    ) {
        NativeFileCrypto.updateChunked(session, data, length = length) { output ->
            check(output.isEmpty()) { "Native HMAC update produced unexpected output" }
        }
    }
}

private class DecryptingRawSource(
    private val input: Source,
    private val decryptor: NativeCryptoSession,
    private val checkOpen: () -> Unit,
) : RawSource {
    private val ciphertext = ByteArray(EncryptedTemporarySpillStorage.STAGING_BUFFER_BYTES)
    private val plaintext = Buffer()
    private var consecutiveZeroReads = 0
    private var finished = false
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Encrypted temporary byte source is closed" }
        checkOpen()
        require(byteCount >= 0L) { "Invalid encrypted temporary byte read size" }
        if (byteCount == 0L) return 0L

        while (plaintext.size == 0L && !finished) {
            val read = input.readAtMostTo(ciphertext)
            if (read == -1) {
                finishDecryption()
            } else if (read == 0) {
                consecutiveZeroReads += 1
                if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                    throw IOException("Encrypted temporary source made no progress while reading")
                }
            } else {
                consecutiveZeroReads = 0
                try {
                    NativeFileCrypto.updateChunked(
                        session = decryptor,
                        data = ciphertext,
                        length = read,
                    ) { output ->
                        plaintext.write(output)
                    }
                } finally {
                    ciphertext.fill(0, fromIndex = 0, toIndex = read)
                }
            }
        }

        if (plaintext.size == 0L) return -1L
        return plaintext.readAtMostTo(sink, byteCount)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            input.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            decryptor.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        } finally {
            ciphertext.fill(0)
            plaintext.readByteArray().fill(0)
        }
        failure?.let { throw it }
    }

    private fun finishDecryption() {
        val finalPlaintext = decryptor.finish()
        try {
            plaintext.write(finalPlaintext)
        } finally {
            finalPlaintext.fill(0)
        }
        finished = true
    }

    private companion object {
        const val MAX_CONSECUTIVE_ZERO_READS = 16
    }
}
