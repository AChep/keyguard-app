package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object FileEncryptionFormat {
    const val TYPE_LENGTH = 1
    const val IV_LENGTH = 16
    const val MAC_LENGTH = 32
    const val HEADER_LENGTH = TYPE_LENGTH + IV_LENGTH + MAC_LENGTH
    const val BUFFER_SIZE = 16 * 1024

    private const val HMAC_SHA256 = "HmacSHA256"

    data class AuthenticatedFrame(
        val type: CipherEncryptor.Type,
        val iv: ByteArray,
        val mac: ByteArray,
        val cipherText: ByteArray,
    )

    data class AuthenticatedHeader(
        val type: CipherEncryptor.Type,
        val iv: ByteArray,
        val mac: ByteArray,
    )

    data class EncryptionKeys(
        val encKey: ByteArray,
        val macKey: ByteArray,
    )

    fun readType(data: ByteArray): CipherEncryptor.Type {
        check(data.isNotEmpty()) { "Invalid encrypted data" }
        return decodeType(data[0])
    }

    fun decodeType(byte: Byte): CipherEncryptor.Type =
        CipherEncryptor.Type.entries
            .firstOrNull { it.byte == byte }
            ?: throw IllegalArgumentException("Can not decrypt data with an unknown type byte of '$byte'!")

    fun parseAuthenticatedFrame(
        data: ByteArray,
    ): AuthenticatedFrame {
        check(data.size >= HEADER_LENGTH) { "Invalid encrypted data" }
        val header = parseAuthenticatedHeader(data, offset = 0)
        val cipherText = data.copyOfRange(HEADER_LENGTH, data.size)
        return AuthenticatedFrame(
            type = header.type,
            iv = header.iv,
            mac = header.mac,
            cipherText = cipherText,
        )
    }

    fun parseAuthenticatedHeader(
        buffer: ByteArray,
        offset: Int,
    ): AuthenticatedHeader {
        check(buffer.size - offset >= HEADER_LENGTH) { "Invalid encrypted data" }

        val type = decodeType(buffer[offset])
        val ivStart = offset + TYPE_LENGTH
        val macStart = ivStart + IV_LENGTH
        val cipherTextStart = macStart + MAC_LENGTH
        return AuthenticatedHeader(
            type = type,
            iv = buffer.copyOfRange(ivStart, macStart),
            mac = buffer.copyOfRange(macStart, cipherTextStart),
        )
    }

    fun requireAesCbc128HmacSha256Keys(key: ByteArray): EncryptionKeys {
        check(key.size >= 32) { "Aes-Cbc-128-Hmac-Sha256 requires a 32-byte key!" }
        return EncryptionKeys(
            encKey = key.copyOfRange(0, 16),
            macKey = key.copyOfRange(16, 32),
        )
    }

    fun requireAesCbc256HmacSha256Keys(
        key: ByteArray,
    ): EncryptionKeys {
        check(key.size >= 64) { "Aes-Cbc-256-Hmac-Sha256 requires a 64-byte key!" }
        return EncryptionKeys(
            encKey = key.copyOfRange(0, 32),
            macKey = key.copyOfRange(32, 64),
        )
    }

    fun createHmac(key: ByteArray): Mac =
        Mac.getInstance(HMAC_SHA256).apply {
            init(SecretKeySpec(key, HMAC_SHA256))
        }

    fun verifyMac(
        expectedMac: ByteArray,
        actualMac: ByteArray,
    ) {
        check(MessageDigest.isEqual(expectedMac, actualMac)) {
            "Message authentication codes do not match!"
        }
    }
}
