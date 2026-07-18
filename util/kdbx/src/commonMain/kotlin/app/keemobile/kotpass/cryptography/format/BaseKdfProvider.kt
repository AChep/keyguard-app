package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.header.KdfParameters.Aes
import app.keemobile.kotpass.database.header.KdfParameters.Argon2
import app.keemobile.kotpass.errors.FormatError
import com.artemchep.keyguard.nativecrypto.NativeArgon2Mode
import com.artemchep.keyguard.nativecrypto.NativeArgon2Version
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
            val ownedSalt = kdfParameters.salt.toByteArray()
            val ownedSecretKey = kdfParameters.secretKey?.toByteArray()
            val ownedAssociatedData = kdfParameters.associatedData?.toByteArray()
            try {
                Argon2Kdf.transformKey(
                    variant = when (kdfParameters.variant) {
                        Argon2.Variant.Argon2d -> NativeArgon2Mode.ARGON2_D
                        Argon2.Variant.Argon2id -> NativeArgon2Mode.ARGON2_ID
                    },
                    version = when (kdfParameters.version) {
                        0x10U -> NativeArgon2Version.VERSION_1_0
                        0x13U -> NativeArgon2Version.VERSION_1_3
                        else -> throw FormatError.InvalidHeader(
                            "Unsupported Argon2 version: ${kdfParameters.version}."
                        )
                    },
                    password = compositeKey,
                    salt = ownedSalt,
                    secretKey = ownedSecretKey,
                    additional = ownedAssociatedData,
                    iterations = kdfParameters.iterations,
                    parallelism = kdfParameters.parallelism,
                    memory = kdfParameters.memory
                )
            } finally {
                ownedSalt.fill(0)
                ownedSecretKey?.fill(0)
                ownedAssociatedData?.fill(0)
            }
        }
    }
}
