package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.nativecrypto.NativeAesCbcPkcs7HmacSha256DecryptSession
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES

internal object NativeFileCrypto {
    fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val type = FileEncryptionFormat.readType(input)
        if (type == CipherEncryptor.Type.AesCbc256_B64) {
            throwLegacyAesUnsupported()
        }
        val frame = FileEncryptionFormat.parseAuthenticatedFrame(input)
        val keys = keys(type, key)
        return try {
            decryptAesCbcHmacSha256(
                encryptionKey = keys.encKey,
                macKey = keys.macKey,
                iv = frame.iv,
                ciphertext = frame.cipherText,
                expectedMac = frame.mac,
            )
        } finally {
            keys.clear()
            frame.iv.fill(0)
            frame.mac.fill(0)
            frame.cipherText.fill(0)
        }
    }

    fun encode(
        data: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        return try {
            val result = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
                encryptionKey = keys.encKey,
                macKey = keys.macKey,
                iv = iv,
                plaintext = data,
            )
            try {
                ByteArray(FileEncryptionFormat.HEADER_LENGTH + result.ciphertext.size).also { output ->
                    output[0] = CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte
                    iv.copyInto(output, destinationOffset = FileEncryptionFormat.TYPE_LENGTH)
                    result.mac.copyInto(
                        output,
                        destinationOffset = FileEncryptionFormat.TYPE_LENGTH + FileEncryptionFormat.IV_LENGTH,
                    )
                    result.ciphertext.copyInto(
                        output,
                        destinationOffset = FileEncryptionFormat.HEADER_LENGTH,
                    )
                }
            } finally {
                result.mac.fill(0)
                result.ciphertext.fill(0)
            }
        } finally {
            keys.clear()
        }
    }

    fun decryptAesCbcHmacSha256(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
    ): ByteArray {
        check(expectedMac.size == FileEncryptionFormat.MAC_LENGTH) {
            AUTHENTICATION_FAILURE_MESSAGE
        }
        return try {
            NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                ciphertext = ciphertext,
                expectedMac = expectedMac,
            )
        } catch (failure: NativeCryptoException) {
            rethrowAuthenticationFailure(failure)
        }
    }

    fun rethrowAuthenticationFailure(failure: NativeCryptoException): Nothing {
        if (failure.code != NativeCryptoErrorCode.AUTHENTICATION_FAILED) throw failure
        throw IllegalStateException(AUTHENTICATION_FAILURE_MESSAGE, failure)
    }

    fun keys(
        type: CipherEncryptor.Type,
        key: ByteArray,
    ): FileEncryptionFormat.EncryptionKeys = when (type) {
        CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
            FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key)

        CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
            FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)

        CipherEncryptor.Type.AesCbc256_B64 -> throwLegacyAesUnsupported()
        else -> throw IllegalArgumentException("Can not decrypt data with a type of '$type'!")
    }

    // The one-shot
    // [NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt] and streaming
    // [NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Encryptor] operations
    // produce the ciphertext and its HMAC together.
    fun hmacSha256(
        key: ByteArray,
        vararg chunks: ByteArray,
    ): ByteArray = NativeCryptoPrimitives.createHmacSha256(key).use { session ->
        chunks.forEach { chunk ->
            updateChunked(session, chunk) { output ->
                check(output.isEmpty()) { "Native HMAC update produced unexpected output." }
            }
        }
        session.finish()
    }

    inline fun updateChunked(
        session: NativeCryptoSession,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        consumeOutput: (ByteArray) -> Unit,
    ) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid native crypto stream range"
        }
        var consumed = 0
        while (consumed < length) {
            val chunkLength = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, length - consumed)
            val output = session.update(data, offset + consumed, chunkLength)
            try {
                consumeOutput(output)
            } finally {
                output.fill(0)
            }
            consumed += chunkLength
        }
    }

    inline fun updateProvisionalChunked(
        session: NativeAesCbcPkcs7HmacSha256DecryptSession,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        consumeProvisionalOutput: (ByteArray) -> Unit,
    ) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid native crypto stream range"
        }
        var consumed = 0
        while (consumed < length) {
            val chunkLength = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, length - consumed)
            val provisionalPlaintext = session.updateProvisional(
                data,
                offset + consumed,
                chunkLength,
            )
            try {
                consumeProvisionalOutput(provisionalPlaintext)
            } finally {
                provisionalPlaintext.fill(0)
            }
            consumed += chunkLength
        }
    }

    fun FileEncryptionFormat.EncryptionKeys.clear() {
        encKey.fill(0)
        macKey.fill(0)
    }

    private const val AUTHENTICATION_FAILURE_MESSAGE: String =
        "Message authentication codes do not match!"
}
