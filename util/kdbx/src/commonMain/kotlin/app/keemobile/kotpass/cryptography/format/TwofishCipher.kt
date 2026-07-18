package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.errors.CryptoError.InvalidCipherText
import app.keemobile.kotpass.errors.CryptoError.InvalidDataLength
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import kotlin.uuid.Uuid

/**
 * Twofish is a symmetric key block cipher, with a block size of 128 bits
 * and key sizes up to 256 bits. Twofish uses pre-computed key-dependent
 * S-boxes and a complex key schedule.
 *
 * **Note:** Twofish is not part of the standard KDBX specification
 * and should be used only for compatibility reasons.
 */
object TwofishCipher : CipherProvider {
    override val uuid: Uuid = Uuid.parse("ad68f29f-576f-4bb9-a36a-d47af965346c")
    override val ivLength = 16U

    override fun createEncryptor(
        key: ByteArray,
        iv: ByteArray,
    ): CipherSession = createSession(encrypt = true, key, iv)

    override fun createDecryptor(
        key: ByteArray,
        iv: ByteArray,
    ): CipherSession = createSession(encrypt = false, key, iv)

    private fun createSession(
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray,
    ): CipherSession {
        validateParameters(key, iv)
        val delegate = if (encrypt) {
            NativeCryptoPrimitives.createTwofishCbcPkcs7Encryptor(key, iv)
        } else {
            NativeCryptoPrimitives.createTwofishCbcPkcs7Decryptor(key, iv)
        }
        return NativeCipherSession(delegate) { error ->
            if (
                error is NativeCryptoException &&
                error.code == NativeCryptoErrorCode.AUTHENTICATION_FAILED
            ) {
                InvalidCipherText("Pad block is corrupted")
            } else {
                error
            }
        }
    }

    private fun validateParameters(
        key: ByteArray,
        iv: ByteArray,
    ) {
        if (key.size !in setOf(16, 24, 32)) {
            throw InvalidDataLength("Twofish key length must be 128/192/256 bits")
        }
        if (iv.size != 16) {
            throw InvalidDataLength("Twofish IV length must be 128 bits")
        }
    }
}
