package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.errors.FormatError
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeStreamCipherAlgorithm
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
sealed class EncryptionSaltGenerator private constructor(
    private val algorithm: NativeStreamCipherAlgorithm,
    private val key: ByteArray,
    private val nonce: ByteArray,
) {
    private var offset = 0L

    /**
     * Get salt using underlying algorithm and advance the counter.
     */
    fun getSalt(length: Int): ByteArray = processBytes(ByteArray(length))

    /**
     * Encrypt/decrypt [input] with salt supplied by underlying
     * algorithm and advance the counter.
     */
    fun processBytes(input: ByteArray): ByteArray {
        val output = NativeCryptoPrimitives.streamCipherXorAtOffset(
            algorithm = algorithm,
            key = key,
            nonce = nonce,
            offset = offset,
            data = input,
        )
        offset += input.size
        return output
    }

    class Salsa20(key: ByteArray) : EncryptionSaltGenerator(
        algorithm = NativeStreamCipherAlgorithm.SALSA20,
        key = sha256(key),
        // Static 'nonce' provided by KeePass specification
        nonce = byteArrayOf(0xe8.toByte(), 0x30, 0x09, 0x4b, 0x97.toByte(), 0x20, 0x5d, 0x2a),
    )

    class ChaCha20 private constructor(
        parameters: StreamParameters,
    ) : EncryptionSaltGenerator(
        algorithm = NativeStreamCipherAlgorithm.CHACHA20,
        key = parameters.key,
        nonce = parameters.nonce,
    ) {
        constructor(key: ByteArray) : this(deriveChaChaParameters(key))
    }

    companion object {
        fun create(id: CrsAlgorithm, key: ByteString): EncryptionSaltGenerator {
            val ownedKey = key.toByteArray()
            return try {
                when (id) {
                    CrsAlgorithm.Salsa20 -> Salsa20(ownedKey)
                    CrsAlgorithm.ChaCha20 -> ChaCha20(ownedKey)
                    else -> throw FormatError.InvalidHeader("Unsupported inner random stream cipher.")
                }
            } finally {
                ownedKey.fill(0)
            }
        }

        private fun deriveChaChaParameters(key: ByteArray): StreamParameters {
            val hash = sha512(key)
            return try {
                StreamParameters(
                    key = hash.copyOfRange(0, 32),
                    nonce = hash.copyOfRange(32, 44),
                )
            } finally {
                hash.fill(0)
            }
        }
    }

    private data class StreamParameters(
        val key: ByteArray,
        val nonce: ByteArray,
    )
}
