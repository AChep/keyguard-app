package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.DecodeException
import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.withHkdfCipherKey
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2

/**
 * Wraps the random master key of a vault with a key derived from the
 * app password, see [com.artemchep.keyguard.common.model.FingerprintPassword.Wrapped]:
 *
 * ```
 * material = Argon2id(password, salt)                  // one run, 32 bytes
 * kek      = HKDF-SHA256(material, info = KEK_HKDF_INFO, 64 bytes)
 * wrapped  = AES-CBC-256 + HMAC-SHA256(kek, master key)
 * ```
 *
 * The functions are blocking: run them off the main thread.
 */
class MasterKeyWrapping(
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
) {
    companion object {
        /** Frozen: the wrapped vaults depend on it. */
        private val KEK_HKDF_INFO = "keyguard/vault-kek/v1".encodeToByteArray()

        private const val KEK_SIZE = 64

        /** The size of a random master key. */
        const val MASTER_KEY_SIZE = 32

        private val CIPHER_TYPE = CipherEncryptor.Type.AesCbc256_HmacSha256_B64
    }

    /**
     * Returns the [masterKey] wrapped with the key the [password]
     * derives under the [salt] and [kdf].
     */
    fun wrap(
        password: ByteArray,
        salt: ByteArray,
        kdf: MasterKdf.Argon2id,
        masterKey: ByteArray,
    ): ByteArray = withKek(password, salt, kdf) { kek ->
        cipherEncryptor
            .encode2(
                cipherType = CIPHER_TYPE,
                plainText = masterKey,
                symmetricCryptoKey = kek,
            )
            .encodeToByteArray()
    }

    /**
     * Returns the master key in the [wrapped] bytes.
     *
     * @throws PasswordMismatchException if the key the [password] derives
     *   does not open the [wrapped] bytes.
     */
    fun unwrap(
        password: ByteArray,
        salt: ByteArray,
        kdf: MasterKdf.Argon2id,
        wrapped: ByteArray,
    ): ByteArray {
        val cipher = wrapped.decodeToString()
        // Never accept another cipher type, for example
        // one without a MAC.
        val masterKey = if (cipher.startsWith("${CIPHER_TYPE.type}.")) {
            withKek(password, salt, kdf) { kek ->
                try {
                    cipherEncryptor.decode2(cipher, kek).data
                } catch (expected: DecodeException) {
                    null
                }
            }
        } else {
            null
        }
        if (masterKey == null || masterKey.size != MASTER_KEY_SIZE) {
            masterKey?.fill(0)
            throw PasswordMismatchException()
        }
        return masterKey
    }

    private inline fun <T> withKek(
        password: ByteArray,
        salt: ByteArray,
        kdf: MasterKdf.Argon2id,
        block: (SymmetricCryptoKey2) -> T,
    ): T = cryptoGenerator
        .masterArgon2id(
            password = password,
            salt = salt,
            kdf = kdf,
        )
        .useAndClear { material ->
            cryptoGenerator.withHkdfCipherKey(
                seed = material,
                salt = null,
                info = KEK_HKDF_INFO,
                length = KEK_SIZE,
                block = block,
            )
        }
}
