package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.errors.CryptoError.AlgorithmUnavailable
import app.keemobile.kotpass.errors.CryptoError.InvalidKey
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.nativecrypto.NativeStreamCipherAlgorithm
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

        override fun createEncryptor(
            key: ByteArray,
            iv: ByteArray,
        ): CipherSession = createAesSession(encrypt = true, key, iv)

        override fun createDecryptor(
            key: ByteArray,
            iv: ByteArray,
        ): CipherSession = createAesSession(encrypt = false, key, iv)

        private fun createAesSession(
            encrypt: Boolean,
            key: ByteArray,
            iv: ByteArray,
        ): CipherSession = try {
            val delegate = if (encrypt) {
                NativeCryptoPrimitives.createAesCbcPkcs7Encryptor(key, iv)
            } else {
                NativeCryptoPrimitives.createAesCbcPkcs7Decryptor(key, iv)
            }
            NativeCipherSession(delegate) { error ->
                when (error) {
                    is UnsupportedOperationException -> {
                        AlgorithmUnavailable(
                            "AES/CBC encryption is not supported in current environment.",
                            error,
                        )
                    }

                    else -> {
                        InvalidKey("Wrong key used for decryption.")
                    }
                }
            }
        } catch (error: UnsupportedOperationException) {
            throw AlgorithmUnavailable(
                "AES/CBC encryption is not supported in current environment.",
                error,
            )
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

        override fun createEncryptor(
            key: ByteArray,
            iv: ByteArray,
        ): CipherSession = ChaCha20CipherSession(key, iv)

        override fun createDecryptor(
            key: ByteArray,
            iv: ByteArray,
        ): CipherSession = ChaCha20CipherSession(key, iv)
    },
}

internal class NativeCipherSession(
    private val delegate: NativeCryptoSession,
    private val mapFailure: (Throwable) -> Throwable = { it },
) : CipherSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = try {
        delegate.update(data, offset, length)
    } catch (error: Throwable) {
        throw mapFailure(error)
    }

    override fun finish(): ByteArray = try {
        delegate.finish()
    } catch (error: Throwable) {
        throw mapFailure(error)
    }

    override fun close() = delegate.close()
}

private class ChaCha20CipherSession(
    key: ByteArray,
    nonce: ByteArray,
) : CipherSession {
    private val key = key.copyOf()
    private val nonce = nonce.copyOf()
    private var offset = 0L
    private var consumed = false

    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        check(!consumed) { "Cipher session is already consumed" }
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid cipher input range"
        }
        if (length == 0) return ByteArray(0)
        val input = if (offset == 0 && length == data.size) {
            data
        } else {
            data.copyOfRange(offset, offset + length)
        }
        return try {
            NativeCryptoPrimitives
                .streamCipherXorAtOffset(
                    algorithm = NativeStreamCipherAlgorithm.CHACHA20,
                    key = key,
                    nonce = nonce,
                    offset = this.offset,
                    data = input,
                ).also {
                    check(this.offset <= Long.MAX_VALUE - length) {
                        "Cipher stream offset overflow"
                    }
                    this.offset += length
                }
        } finally {
            if (input !== data) input.fill(0)
        }
    }

    override fun finish(): ByteArray {
        check(!consumed) { "Cipher session is already consumed" }
        consumed = true
        clear()
        return ByteArray(0)
    }

    override fun close() {
        if (!consumed) {
            consumed = true
            clear()
        }
    }

    private fun clear() {
        key.fill(0)
        nonce.fill(0)
    }
}
