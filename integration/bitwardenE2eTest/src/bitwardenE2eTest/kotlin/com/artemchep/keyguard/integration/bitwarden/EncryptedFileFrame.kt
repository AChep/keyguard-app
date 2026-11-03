package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import java.security.SecureRandom

/**
 * The Bitwarden encrypted file format of attachments and Send files:
 * `[0x02][iv 16][mac 32][ciphertext]`, AES-CBC-256 with HMAC-SHA256 over `iv || ciphertext`.
 * The 64-byte key is the AES key, then the HMAC key.
 */
internal object EncryptedFileFrame {
    private const val TYPE_AES_CBC_256_HMAC_SHA256: Byte = 2
    private const val IV_LENGTH = 16
    private const val MAC_LENGTH = 32
    private const val HEADER_LENGTH = 1 + IV_LENGTH + MAC_LENGTH
    private const val KEY_LENGTH = 64
    private const val ENC_KEY_LENGTH = 32

    private val random = SecureRandom()

    fun encrypt(
        key64: ByteArray,
        plaintext: ByteArray,
        iv: ByteArray = ByteArray(IV_LENGTH).also(random::nextBytes),
    ): ByteArray {
        require(key64.size == KEY_LENGTH) { "Expected a 64-byte key, got ${key64.size}" }
        val result = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey = key64.copyOfRange(0, ENC_KEY_LENGTH),
            macKey = key64.copyOfRange(ENC_KEY_LENGTH, KEY_LENGTH),
            iv = iv,
            plaintext = plaintext,
        )
        return byteArrayOf(TYPE_AES_CBC_256_HMAC_SHA256) + iv + result.mac + result.ciphertext
    }

    /** Throws if the frame is not type 2 or fails authentication. */
    fun decrypt(key64: ByteArray, frame: ByteArray): ByteArray {
        require(key64.size == KEY_LENGTH) { "Expected a 64-byte key, got ${key64.size}" }
        require(frame.size > HEADER_LENGTH) { "The frame is shorter than its header" }
        require(frame[0] == TYPE_AES_CBC_256_HMAC_SHA256) { "Unsupported frame type ${frame[0]}" }
        return NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
            encryptionKey = key64.copyOfRange(0, ENC_KEY_LENGTH),
            macKey = key64.copyOfRange(ENC_KEY_LENGTH, KEY_LENGTH),
            iv = frame.copyOfRange(1, 1 + IV_LENGTH),
            ciphertext = frame.copyOfRange(HEADER_LENGTH, frame.size),
            expectedMac = frame.copyOfRange(1 + IV_LENGTH, HEADER_LENGTH),
        )
    }
}
