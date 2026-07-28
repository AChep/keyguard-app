package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.extensions.clear
import com.artemchep.keyguard.util.foundation.crypto.KdfLimits
import com.artemchep.keyguard.util.foundation.crypto.aesEcbNoPaddingTransform
import com.artemchep.keyguard.util.foundation.crypto.sha256

internal object AesKdf {
    fun transformKey(
        key: ByteArray,
        seed: ByteArray,
        rounds: ULong
    ): ByteArray {
        var transformed: ByteArray? = null
        return try {
            require(rounds <= KdfLimits.MaxAesRounds) {
                "AES-KDF rounds exceed the allowed maximum."
            }
            aesEcbNoPaddingTransform(
                key = seed,
                data = key,
                rounds = rounds.toInt(),
            ).also { transformed = it }
                .let(::sha256)
        } catch (_: Throwable) {
            throw CryptoError.InvalidKey("Wrong KDF seed used for decryption.")
        } finally {
            transformed?.clear()
        }
    }
}
