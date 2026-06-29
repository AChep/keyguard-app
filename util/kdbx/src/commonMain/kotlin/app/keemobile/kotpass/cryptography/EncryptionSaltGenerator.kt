package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.cryptography.engines.ChaCha7539Engine
import app.keemobile.kotpass.cryptography.engines.Salsa20Engine
import app.keemobile.kotpass.errors.FormatError
import com.artemchep.keyguard.util.foundation.crypto.sha256
import com.artemchep.keyguard.util.foundation.crypto.sha512
import okio.ByteString

/**
 * Used as inner encryption to improve process memory protection, it does not enhance
 * the cryptographic security of the KDBX file format itself.
 *
 * **Problem**: XML parsers use regular strings that persist in process memory,
 * making sensitive data vulnerable.
 *
 * **Solution**: store sensitive data encrypted within the XML document using
 * the inner header’s encryption algorithm and key.
 *
 * **Note**:
 * - Uses stream cipher *without* state reset between protected fields.
 * - Encryption order matters: data encrypted sequentially using consecutive cipher output bytes.
 */
sealed class EncryptionSaltGenerator {
    /**
     * Get salt using underlying algorithm and advance the counter.
     */
    abstract fun getSalt(length: Int): ByteArray

    /**
     * Encrypt/decrypt [input] with salt supplied by underlying
     * algorithm and advance the counter.
     */
    abstract fun processBytes(input: ByteArray): ByteArray

    class Salsa20(key: ByteArray) : EncryptionSaltGenerator() {
        // Static 'nonce' provided by KeePass specification
        private val nonce = byteArrayOf(0xe8.toByte(), 0x30, 0x09, 0x4b, 0x97.toByte(), 0x20, 0x5d, 0x2a)

        private val engine = Salsa20Engine().apply {
            init(sha256(key), nonce)
        }

        override fun getSalt(length: Int) = engine.getBytes(length)

        override fun processBytes(input: ByteArray) = engine.processBytes(input)
    }

    class ChaCha20(key: ByteArray) : EncryptionSaltGenerator() {
        private val engine = ChaCha7539Engine().apply {
            val hash = sha512(key)
            init(
                key = hash.sliceArray(0 until 32),
                iv = hash.sliceArray(32 until 44)
            )
        }

        override fun getSalt(length: Int) = engine.getBytes(length)

        override fun processBytes(input: ByteArray) = engine.processBytes(input)
    }

    companion object {
        fun create(id: CrsAlgorithm, key: ByteString) = when (id) {
            CrsAlgorithm.Salsa20 -> Salsa20(key.toByteArray())
            CrsAlgorithm.ChaCha20 -> ChaCha20(key.toByteArray())
            else -> throw FormatError.InvalidHeader("Unsupported inner random stream cipher.")
        }
    }
}
