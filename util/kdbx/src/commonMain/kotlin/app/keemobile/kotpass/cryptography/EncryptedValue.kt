package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.io.encodeBase64
import com.artemchep.keyguard.util.foundation.crypto.sha256
import kotlin.experimental.xor

/**
 * Applies simple XOR encryption to make value harder
 * to identify and extract from process memory.
 *
 * @property value encrypted raw data.
 * @property salt which was used on the value.
 */
class EncryptedValue(
    private val value: ByteArray,
    private val salt: ByteArray
) {
    /**
     * Length of encrypted value in bytes.
     */
    val byteLength: Int get() = value.size

    /**
     * Decrypts value and parses as [UTF_8][Charsets.UTF_8] string.
     */
    val text: String get() = getBinary().decodeToString()

     /**
     * Decrypts value and calculates SHA256.
     */
    fun getHash() = sha256(getBinary())

    /**
     * Decrypts value and returns raw bytes.
     */
    fun getBinary(): ByteArray {
        val bytes = ByteArray(value.size)

        for (i in bytes.indices) {
            bytes[i] = value[i] xor salt[i]
        }
        return bytes
    }

    /**
     * Encodes value with [newSalt].
     */
    fun setSalt(newSalt: ByteArray) {
        for (i in value.indices) {
            value[i] = (value[i] xor salt[i]) xor newSalt[i]
            salt[i] = newSalt[i]
        }
    }

    /**
     * Decrypts value and returns as base 64.
     */
    fun toBase64(): String = getBinary().encodeBase64()

    override fun toString(): String = value.encodeBase64()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedValue) return false

        return value.contentEquals(other.value) &&
            salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = value.contentHashCode()
        result = 31 * result + salt.contentHashCode()

        return result
    }

    companion object {
        fun fromString(text: String) = fromBinary(text.encodeToByteArray())

        fun fromBase64(base64: String) = fromBinary(base64.decodeBase64ToArray())

        fun fromBinary(
            bytes: ByteArray,
            random: SecureRandom = SecureRandom()
        ): EncryptedValue {
            val salt = ByteArray(bytes.size)
            random.nextBytes(salt)

            for (i in bytes.indices) {
                bytes[i] = bytes[i] xor salt[i]
            }
            return EncryptedValue(bytes, salt)
        }
    }
}
