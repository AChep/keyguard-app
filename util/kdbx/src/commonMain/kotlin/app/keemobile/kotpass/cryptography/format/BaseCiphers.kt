package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.cryptography.engines.ChaCha7539Engine
import app.keemobile.kotpass.errors.CryptoError.AlgorithmUnavailable
import app.keemobile.kotpass.errors.CryptoError.InvalidKey
import com.artemchep.keyguard.util.foundation.crypto.aesCbcPkcs7Decrypt
import com.artemchep.keyguard.util.foundation.crypto.aesCbcPkcs7Encrypt
import kotlin.uuid.Uuid

/**
 * Default provider implementations for the core KeePass ciphers.
 */
enum class BaseCiphers : CipherProvider {
    /**
     * AES-256, also known as Rijndael, is a symmetric-key block cipher specified
     * in the Advanced Encryption Standard (AES). It uses a 256-bit key for
     * encryption and decryption, and operates on a fixed block size of 128 bits.
     * AES-256 is widely recognized for its high level of security.
     */
    Aes {
        override val uuid: Uuid = Uuid.parse("31c1f2e6-bf71-4350-be58-05216afc5aff")
        override val ivLength = 16U

        override fun encrypt(
            key: ByteArray,
            iv: ByteArray,
            data: ByteArray
        ): ByteArray = processBytes(encrypt = true, key, iv, data)

        override fun decrypt(
            key: ByteArray,
            iv: ByteArray,
            data: ByteArray
        ): ByteArray = processBytes(encrypt = false, key, iv, data)

        private fun processBytes(
            encrypt: Boolean,
            key: ByteArray,
            iv: ByteArray,
            data: ByteArray
        ): ByteArray = try {
            if (encrypt) {
                aesCbcPkcs7Encrypt(key, iv, data)
            } else {
                aesCbcPkcs7Decrypt(key, iv, data)
            }
        } catch (e: UnsupportedOperationException) {
            throw AlgorithmUnavailable("AES/CBC encryption is not supported in current environment.")
        } catch (_: Throwable) {
            throw InvalidKey("Wrong key used for decryption.")
        }
    },

    /**
     * ChaCha20 is a stream cipher developed by Daniel J. Bernstein, based on the
     * Salsa20 cipher. It was designed to increase diffusion and performance on various
     * architectures. It is widely used for its efficiency, security, and lack
     * of patent restrictions.
     */
    ChaCha20 {
        override val uuid: Uuid = Uuid.parse("d6038a2b-8b6f-4cb5-a524-339a31dbb59a")
        override val ivLength = 12U

        override fun encrypt(
            key: ByteArray,
            iv: ByteArray,
            data: ByteArray
        ): ByteArray = ChaCha7539Engine()
            .apply { init(key, iv) }
            .processBytes(data)

        override fun decrypt(
            key: ByteArray,
            iv: ByteArray,
            data: ByteArray
        ): ByteArray = ChaCha7539Engine()
            .apply { init(key, iv) }
            .processBytes(data)
    }
}
