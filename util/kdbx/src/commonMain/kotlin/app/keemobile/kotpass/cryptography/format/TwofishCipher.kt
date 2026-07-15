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
        data: ByteArray,
    ): ByteArray {
        if (key.size !in setOf(16, 24, 32)) {
            throw InvalidDataLength("Twofish key length must be 128/192/256 bits")
        }
        if (iv.size != 16) {
            throw InvalidDataLength("Twofish IV length must be 128 bits")
        }
        if (!encrypt && (data.isEmpty() || data.size % 16 != 0)) {
            throw InvalidDataLength("Last block incomplete in decryption")
        }
        return try {
            if (encrypt) {
                NativeCryptoPrimitives.twofishCbcPkcs7Encrypt(key, iv, data)
            } else {
                NativeCryptoPrimitives.twofishCbcPkcs7Decrypt(key, iv, data)
            }
        } catch (e: NativeCryptoException) {
            if (e.code == NativeCryptoErrorCode.AUTHENTICATION_FAILED) {
                throw InvalidCipherText("Pad block is corrupted")
            }
            throw e
        }
    }
}
