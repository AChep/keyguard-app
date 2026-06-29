package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.extensions.clear
import com.artemchep.keyguard.util.foundation.crypto.aesEcbNoPaddingEncrypt
import com.artemchep.keyguard.util.foundation.crypto.sha256

internal object AesKdf {
    fun transformKey(
        key: ByteArray,
        seed: ByteArray,
        rounds: ULong
    ): ByteArray {
        val bytes = key.copyOf()

        return try {
            repeat(rounds.toInt()) {
                aesEcbNoPaddingEncrypt(seed, bytes).copyInto(bytes)
            }
            sha256(bytes)
        } catch (_: Throwable) {
            throw CryptoError.InvalidKey("Wrong KDF seed used for decryption.")
        } finally {
            bytes.clear()
        }
    }
}
