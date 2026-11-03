package com.artemchep.keyguard.common.service.imports.impl.bitwarden

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.api.generateSecrets
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.model.PreLogin

private const val KDF_TYPE_PBKDF2 = 0
private const val KDF_TYPE_ARGON2ID = 1

/**
 * Reads the KDF settings of a password-protected export, or `null` for an
 * unknown function or settings outside the bounds Keyguard computes.
 *
 * PBKDF2 goes down to [PreLogin.Hash.Pbkdf2.ITERATIONS_FLOOR], lower than the
 * login minimum on purpose: a weak file is still the user's own file, while
 * the upper bounds keep a hostile one from stalling the device.
 */
internal fun bitwardenExportKdfOrNull(
    type: Int?,
    iterations: Int?,
    memoryMb: Int?,
    parallelism: Int?,
): PreLogin.Hash? = when (type) {
    KDF_TYPE_PBKDF2 -> {
        val pbkdf2 = PreLogin.Hash.Pbkdf2
        iterations
            ?.takeIf { it in pbkdf2.ITERATIONS_FLOOR..pbkdf2.ITERATIONS_MAX }
            ?.let { PreLogin.Hash.Pbkdf2(iterationsCount = it) }
    }

    KDF_TYPE_ARGON2ID -> {
        val argon2 = PreLogin.Hash.Argon2id
        val valid = iterations != null &&
            memoryMb != null &&
            parallelism != null &&
            iterations in argon2.ITERATIONS_MIN..argon2.ITERATIONS_MAX &&
            memoryMb in argon2.MEMORY_MB_MIN..argon2.MEMORY_MB_MAX &&
            parallelism in argon2.PARALLELISM_MIN..argon2.PARALLELISM_MAX
        if (valid) {
            PreLogin.Hash.Argon2id(
                iterationsCount = iterations,
                memoryMb = memoryMb,
                parallelism = parallelism,
            )
        } else {
            null
        }
    }

    else -> null
}

/**
 * Derives the key of a password-protected export the way Bitwarden derives
 * an account key from the master password, with the file's own salt.
 */
internal fun deriveBitwardenExportKey(
    cryptoGenerator: CryptoGenerator,
    password: String,
    salt: String,
    kdf: PreLogin.Hash,
): SymmetricCryptoKey2 {
    val secrets = generateSecrets(
        cryptoGenerator = cryptoGenerator,
        password = password,
        hashConfig = kdf,
        salt = salt,
    )
    return SymmetricCryptoKey2(
        data = secrets.encryptionKey + secrets.macKey,
    )
}
