package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NativeAesCbcPkcs7HmacSha256EncryptSession
import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import com.artemchep.keyguard.util.foundation.io.SpillStorage
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.buffered

internal class EncryptedTemporarySpillStorage private constructor(
    private val storage: PrivateTemporaryStorage,
    private val keys: FileEncryptionFormat.EncryptionKeys,
    private val iv: ByteArray,
) : SpillStorage {
    private val ciphertextSink = storage.sink().buffered()
    private var encryptor: NativeAesCbcPkcs7HmacSha256EncryptSession? =
        NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Encryptor(
            encryptionKey = keys.encKey,
            macKey = keys.macKey,
            iv = iv,
        )
    private var expectedMac: ByteArray? = null
    private var ciphertextBytes = 0L
    private var sealed = false
    private var replayed = false
    private var closed = false

    override fun write(source: ByteArray, startIndex: Int, endIndex: Int) {
        checkWritable()
        require(startIndex in 0..endIndex && endIndex <= source.size) {
            "Invalid encrypted spill write range"
        }
        val session = checkNotNull(encryptor)
        var offset = startIndex
        while (offset < endIndex) {
            val length = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, endIndex - offset)
            val ciphertext = session.update(source, offset, length)
            try {
                ciphertextSink.write(ciphertext)
                ciphertextBytes += ciphertext.size
            } finally {
                ciphertext.fill(0)
            }
            offset += length
        }
    }

    override fun seal() {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is already sealed" }
        val session = checkNotNull(encryptor)
        try {
            val result = session.finish()
            try {
                ciphertextSink.write(result.ciphertext)
                ciphertextBytes += result.ciphertext.size
                ciphertextSink.close()
                storage.sealForReading()
                expectedMac = result.mac.copyOf()
            } finally {
                result.ciphertext.fill(0)
                result.mac.fill(0)
            }
            sealed = true
        } finally {
            session.close()
            encryptor = null
        }
    }

    override fun replayTo(output: Sink) {
        check(!closed) { "Encrypted spill storage is closed" }
        check(sealed) { "Encrypted spill storage must be sealed before replay" }
        check(!replayed) { "Encrypted spill storage has already been replayed" }
        replayed = true

        authenticateCiphertext()
        storage.rewind()
        NativeCryptoPrimitives.createAesCbcPkcs7Decryptor(
            key = keys.encKey,
            iv = iv,
        ).use { decryptor ->
            storage.source().buffered().use { source ->
                source.consumeWithErasedBuffer(bufferSize = STAGING_BUFFER_BYTES) { data, length ->
                    writeSessionOutput(decryptor, data, length, output)
                }
            }
            val finalPlaintext = decryptor.finish()
            try {
                output.write(finalPlaintext)
            } finally {
                finalPlaintext.fill(0)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            encryptor?.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            ciphertextSink.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        try {
            storage.close()
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        } finally {
            with(NativeFileCrypto) { keys.clear() }
            iv.fill(0)
            expectedMac?.fill(0)
        }
        failure?.let { throw it }
    }

    private fun authenticateCiphertext() {
        val expected = checkNotNull(expectedMac)
        storage.rewind()
        val actual = NativeCryptoPrimitives.createHmacSha256(keys.macKey).use { hmac ->
            updateHmac(hmac, iv, iv.size)
            var bytesRead = 0L
            storage.source().buffered().use { source ->
                source.consumeWithErasedBuffer(bufferSize = STAGING_BUFFER_BYTES) { data, length ->
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
            if (!actual.constantTimeEquals(expected)) {
                throw IOException("Encrypted temporary spill authentication failed")
            }
        } finally {
            actual.fill(0)
        }
    }

    private fun updateHmac(session: NativeCryptoSession, data: ByteArray, length: Int) {
        NativeFileCrypto.updateChunked(session, data, length = length) { output ->
            check(output.isEmpty()) { "Native HMAC update produced unexpected output" }
        }
    }

    private fun writeSessionOutput(
        session: NativeCryptoSession,
        data: ByteArray,
        length: Int,
        output: Sink,
    ) {
        NativeFileCrypto.updateChunked(session, data, length = length) { plaintext ->
            output.write(plaintext)
        }
    }

    private fun checkWritable() {
        check(!closed) { "Encrypted spill storage is closed" }
        check(!sealed) { "Encrypted spill storage is sealed" }
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
