package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.cryptography.engines.Argon2Engine

internal object Argon2Kdf {
    fun transformKey(
        variant: Argon2Engine.Variant,
        version: Argon2Engine.Version,
        password: ByteArray,
        secretKey: ByteArray?,
        additional: ByteArray?,
        salt: ByteArray,
        iterations: ULong,
        parallelism: UInt,
        memory: ULong
    ): ByteArray {
        val result = ByteArray(32)
        Argon2Engine(
            variant = variant,
            salt = salt,
            secret = secretKey,
            additional = additional,
            iterations = iterations.toInt(),
            parallelism = parallelism.toInt(),
            memory = memory.toInt() / 1024,
            version = version
        ).generateBytes(password, result)

        return result
    }
}
