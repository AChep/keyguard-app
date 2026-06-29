package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.cryptography.engines.Argon2Engine
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.header.KdfParameters.Aes
import app.keemobile.kotpass.database.header.KdfParameters.Argon2

internal object BaseKdfProvider : KdfProvider {
    override fun transformKey(
        kdfParameters: KdfParameters,
        compositeKey: ByteArray
    ): ByteArray = when (kdfParameters) {
        is Aes -> {
            AesKdf.transformKey(
                key = compositeKey,
                seed = kdfParameters.seed.toByteArray(),
                rounds = kdfParameters.rounds
            )
        }
        is Argon2 -> {
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
