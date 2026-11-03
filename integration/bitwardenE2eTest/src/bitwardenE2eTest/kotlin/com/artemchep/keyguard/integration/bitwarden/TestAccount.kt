package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/** The KDF that derives an account's master key. */
internal sealed interface TestKdf {
    /** Keyguard rejects fewer iterations than the default. */
    data class Pbkdf2(
        val iterations: Int = PreLogin.Hash.Pbkdf2.ITERATIONS_MIN,
    ) : TestKdf

    /** Keyguard requires at least 2 iterations, 16 MiB and 1 lane. The defaults are Bitwarden's. */
    data class Argon2id(
        val iterations: Int = 3,
        val memoryMib: Int = 64,
        val parallelism: Int = 4,
    ) : TestKdf
}

/** The KDF of a Keyguard prelogin result. */
internal fun PreLogin.Hash.toTestKdf(): TestKdf = when (this) {
    is PreLogin.Hash.Pbkdf2 -> TestKdf.Pbkdf2(iterations = iterationsCount)

    is PreLogin.Hash.Argon2id -> TestKdf.Argon2id(
        iterations = iterationsCount,
        memoryMib = memoryMb,
        parallelism = parallelism,
    )
}

/** The KDF fields of a registration request. */
internal fun JsonObjectBuilder.putKdf(kdf: TestKdf) = when (kdf) {
    is TestKdf.Pbkdf2 -> {
        put("kdf", 0)
        put("kdfIterations", kdf.iterations)
        put("kdfMemory", JsonNull)
        put("kdfParallelism", JsonNull)
    }

    is TestKdf.Argon2id -> {
        put("kdf", 1)
        put("kdfIterations", kdf.iterations)
        put("kdfMemory", kdf.memoryMib)
        put("kdfParallelism", kdf.parallelism)
    }
}

/** An account registered by [VaultwardenAccounts.register], with its client-side secrets. */
internal class TestAccount(
    val email: String,
    val password: String,
    val kdf: TestKdf,
    val masterKey: ByteArray,
    val masterPasswordHash: String,
    /** 64 bytes: the AES key, then the HMAC key. */
    val userKey: ByteArray,
    /** PKCS#8 DER. */
    val privateKey: ByteArray,
)

internal class RawLogin(
    val accessToken: String,
    /** The user key, encrypted with the stretched master key. */
    val key: String,
    /** The "remember this device" token; present only when the login asked for it. */
    val twoFactorToken: String?,
)

/** The two-factor fields of a password login. */
internal data class RawTwoFactor(
    /** A Bitwarden provider id, such as [AUTHENTICATOR]. */
    val provider: Int,
    val token: String,
    val remember: Boolean = false,
) {
    companion object {
        const val AUTHENTICATOR = 0
    }
}
