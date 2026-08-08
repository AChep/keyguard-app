package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeRsaOaepHash

/**
 * Hybrid seal-to-public-key envelope for pending usage-history rows.
 *
 * The exposed database is readable without the master password, so a
 * queued row must be opaque at rest. Each row gets fresh AES + HMAC
 * keys, wrapped with RSA-OAEP to a public key whose private half lives
 * in the vault database; sealing therefore needs no unlocked session
 * while opening does.
 *
 * Blob layout:
 * `version(1) | wrappedLen(2, BE) | wrapped | iv(16) | mac(32) | ciphertext`
 *
 * The envelope guarantees confidentiality, not integrity against a
 * local writer: the sealing public key is itself readable at the
 * exposed tier, so anyone with write access there can fabricate a
 * valid-looking event (or delete the queue outright). That is inherent
 * to letting locked-state code write history and is accepted.
 */
object PendingUsageHistoryEnvelope {
    private const val VERSION: Int = 1

    private const val ENCRYPTION_KEY_BYTES = 32
    private const val MAC_KEY_BYTES = 32
    private const val IV_BYTES = 16
    private const val MAC_BYTES = 32
    private const val HEADER_BYTES = 3

    private const val U8_MASK = 0xFF
    private const val U8_BITS = 8
    private const val MAX_WRAPPED_BYTES = 0xFFFF

    fun seal(
        publicKeySpki: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val encryptionKey = NativeCryptoPrimitives.randomBytes(ENCRYPTION_KEY_BYTES)
        val macKey = NativeCryptoPrimitives.randomBytes(MAC_KEY_BYTES)
        val keyMaterial = encryptionKey + macKey
        try {
            val wrapped = NativeCryptoPrimitives.rsaOaepEncrypt(
                publicKeySpki = publicKeySpki,
                plaintext = keyMaterial,
                hash = NativeRsaOaepHash.SHA_256,
            )
            require(wrapped.size in 1..MAX_WRAPPED_BYTES) {
                "Wrapped key does not fit the envelope header"
            }
            val iv = NativeCryptoPrimitives.randomBytes(IV_BYTES)
            val encrypted = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                plaintext = plaintext,
            )
            val blob = ByteArray(
                HEADER_BYTES + wrapped.size + IV_BYTES + MAC_BYTES + encrypted.ciphertext.size,
            )
            var offset = 0
            blob[offset++] = VERSION.toByte()
            blob[offset++] = (wrapped.size ushr U8_BITS and U8_MASK).toByte()
            blob[offset++] = (wrapped.size and U8_MASK).toByte()
            wrapped.copyInto(blob, offset)
            offset += wrapped.size
            iv.copyInto(blob, offset)
            offset += IV_BYTES
            encrypted.mac.copyInto(blob, offset)
            offset += MAC_BYTES
            encrypted.ciphertext.copyInto(blob, offset)
            return blob
        } finally {
            encryptionKey.fill(0)
            macKey.fill(0)
            keyMaterial.fill(0)
        }
    }

    /**
     * Opens a sealed blob. Throws on any structural or cryptographic
     * failure; callers treat a failed row as undecryptable and drop it.
     */
    fun open(
        privateKeyPkcs8: ByteArray,
        blob: ByteArray,
    ): ByteArray {
        require(blob.size > HEADER_BYTES) { "Envelope is truncated" }
        require(blob[0].toInt() == VERSION) { "Unsupported envelope version" }
        val wrappedSize = (blob[1].toInt() and U8_MASK shl U8_BITS) or
                (blob[2].toInt() and U8_MASK)
        val ivOffset = HEADER_BYTES + wrappedSize
        val macOffset = ivOffset + IV_BYTES
        val ciphertextOffset = macOffset + MAC_BYTES
        require(wrappedSize > 0 && blob.size > ciphertextOffset) { "Envelope is truncated" }
        val wrapped = blob.copyOfRange(HEADER_BYTES, ivOffset)
        val iv = blob.copyOfRange(ivOffset, macOffset)
        val mac = blob.copyOfRange(macOffset, ciphertextOffset)
        val ciphertext = blob.copyOfRange(ciphertextOffset, blob.size)
        val keyMaterial = NativeCryptoPrimitives.rsaOaepDecrypt(
            privateKeyPkcs8 = privateKeyPkcs8,
            ciphertext = wrapped,
            hash = NativeRsaOaepHash.SHA_256,
        )
        try {
            require(keyMaterial.size == ENCRYPTION_KEY_BYTES + MAC_KEY_BYTES) {
                "Envelope key material has an unexpected size"
            }
            val encryptionKey = keyMaterial.copyOfRange(0, ENCRYPTION_KEY_BYTES)
            val macKey = keyMaterial.copyOfRange(ENCRYPTION_KEY_BYTES, keyMaterial.size)
            try {
                return NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = ciphertext,
                    expectedMac = mac,
                )
            } finally {
                encryptionKey.fill(0)
                macKey.fill(0)
            }
        } finally {
            keyMaterial.fill(0)
        }
    }
}
