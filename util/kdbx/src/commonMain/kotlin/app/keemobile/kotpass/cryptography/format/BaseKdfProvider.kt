package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.cryptography.engines.Argon2Engine
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.header.KdfParameters.Aes
import app.keemobile.kotpass.database.header.KdfParameters.Argon2
import app.keemobile.kotpass.errors.FormatError
import com.artemchep.keyguard.util.foundation.crypto.KdfLimits

internal object BaseKdfProvider : KdfProvider {
    override fun transformKey(
        kdfParameters: KdfParameters,
        compositeKey: ByteArray
    ): ByteArray = when (kdfParameters) {
        is Aes -> {
            if (kdfParameters.rounds > KdfLimits.MaxAesRounds) {
                throw FormatError.InvalidHeader("AES-KDF rounds exceed the allowed maximum.")
            }
            AesKdf.transformKey(
                key = compositeKey,
                seed = kdfParameters.seed.toByteArray(),
                rounds = kdfParameters.rounds
            )
        }
        is Argon2 -> {
            if (kdfParameters.parallelism < 1U || kdfParameters.parallelism > KdfLimits.MaxArgon2Parallelism) {
                throw FormatError.InvalidHeader("Argon2 parallelism is out of the allowed range.")
            }
            if (kdfParameters.iterations < 1UL || kdfParameters.iterations > KdfLimits.MaxArgon2Iterations) {
                throw FormatError.InvalidHeader("Argon2 iterations are out of the allowed range.")
            }
            if (kdfParameters.memory < KdfLimits.MinArgon2Memory || kdfParameters.memory > KdfLimits.MaxArgon2Memory) {
                throw FormatError.InvalidHeader("Argon2 memory is out of the allowed range.")
            }
            Argon2Kdf.transformKey(
                variant = when (kdfParameters.variant) {
                    Argon2.Variant.Argon2d -> Argon2Engine.Variant.Argon2d
                    Argon2.Variant.Argon2id -> Argon2Engine.Variant.Argon2id
                },
                version = Argon2Engine.Version.from(kdfParameters.version),
                password = compositeKey,
                salt = kdfParameters.salt.toByteArray(),
                secretKey = kdfParameters.secretKey?.toByteArray(),
                additional = kdfParameters.associatedData?.toByteArray(),
                iterations = kdfParameters.iterations,
                parallelism = kdfParameters.parallelism,
                memory = kdfParameters.memory
            )
        }
    }
}
