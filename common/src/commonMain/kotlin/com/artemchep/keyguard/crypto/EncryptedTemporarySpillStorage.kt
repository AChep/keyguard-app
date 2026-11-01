package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import com.artemchep.keyguard.nativecrypto.NativeAesCbcPkcs7HmacSha256EncryptSession
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.consumeWithErasedBuffer
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Encrypts provisional plaintext before spilling it
 * into private temporary storage.
 */
internal class EncryptedTemporarySpillStorage private constructor(
    storage: PrivateTemporaryStorage,
    private val keys: FileEncryptionFormat.EncryptionKeys,
    private val iv: ByteArray,
    checkCancellation: () -> Unit,
) : EncryptingSpillWriter<ByteSnapshot>(storage, checkCancellation) {
    private var encryptor: NativeAesCbcPkcs7HmacSha256EncryptSession? =
        NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Encryptor(
            encryptionKey = keys.encKey,
            macKey = keys.macKey,
            iv = iv,
        )
    private var plaintextBytes = 0L
    private var ciphertextBytes = 0L

    override fun absorb(bytes: ByteArray, length: Int) {
        val session = checkNotNull(encryptor)
        var offset = 0
        while (offset < length) {
            checkCancellation()
            val chunkLength = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, length - offset)
            val ciphertext = session.update(bytes, offset, chunkLength)
            try {
                checkCancellation()
                ciphertextSink.write(ciphertext)
                ciphertextBytes += ciphertext.size
                plaintextBytes += chunkLength
            } finally {
                ciphertext.fill(0)
            }
            offset += chunkLength
        }
    }

    override fun finishSeal(): ByteSnapshot {
        val session = checkNotNull(encryptor)
        encryptor = null
        val expectedMac = session.use {
            val result = session.finish()
            try {
                checkCancellation()
                ciphertextSink.write(result.ciphertext)
                ciphertextBytes += result.ciphertext.size
                ciphertextSink.close()
                storage.sealForReading()
                result.mac.copyOf()
            } finally {
                result.ciphertext.fill(0)
                result.mac.fill(0)
            }
        }
        return EncryptedTemporaryByteSnapshot(
            storage = storage,
            keys = keys,
            iv = iv,
            expectedMac = expectedMac,
            ciphertextBytes = ciphertextBytes,
            size = plaintextBytes,
            checkCancellation = checkCancellation,
        )
    }

    override fun closeEncryptor() {
        encryptor?.close()
    }

    override fun releaseSecrets() {
        with(NativeFileCrypto) { keys.clear() }
        iv.fill(0)
    }

    companion object {
        /** Takes ownership of [storage], including when initialization fails. */
        fun create(
            storage: PrivateTemporaryStorage,
            checkCancellation: () -> Unit = {},
        ): EncryptedTemporarySpillStorage = create(
            storage = storage,
            checkCancellation = checkCancellation,
            randomBytes = NativeCryptoPrimitives::randomBytes,
        )

        internal fun create(
            storage: PrivateTemporaryStorage,
            checkCancellation: () -> Unit = {},
            randomBytes: (Int) -> ByteArray,
        ): EncryptedTemporarySpillStorage = createOwning(storage, checkCancellation) { wipeOnFailure ->
            val keyMaterial = randomBytes(STAGING_KEY_BYTES)
            val keys = try {
                FileEncryptionFormat.requireAesCbc256HmacSha256Keys(keyMaterial)
            } finally {
                keyMaterial.fill(0)
            }
            wipeOnFailure += { with(NativeFileCrypto) { keys.clear() } }
            val iv = randomBytes(FileEncryptionFormat.IV_LENGTH)
            wipeOnFailure += { iv.fill(0) }
            EncryptedTemporarySpillStorage(
                storage = storage,
                keys = keys,
                iv = iv,
                checkCancellation = checkCancellation,
            )
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
    private val checkCancellation: () -> Unit,
) : ByteSnapshot {
    private var closed = false

    override fun openSource(): Source {
        check(!closed) { "Encrypted temporary byte snapshot is closed" }
        checkCancellation()
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
                checkCancellation = checkCancellation,
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
                    checkCancellation = checkCancellation,
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
            checkCancellation()
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
    private val checkCancellation: () -> Unit,
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
        checkCancellation()
        require(byteCount >= 0L) { "Invalid encrypted temporary byte read size" }
        if (byteCount == 0L) return 0L

        while (plaintext.size == 0L && !finished) {
            checkCancellation()
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
                        checkCancellation()
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
        checkCancellation()
        val finalPlaintext = decryptor.finish()
        try {
            checkCancellation()
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
