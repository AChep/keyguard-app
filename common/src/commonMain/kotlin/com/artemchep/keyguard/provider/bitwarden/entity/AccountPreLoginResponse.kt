package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class AccountPreLoginResponse(
    @JsonNames("kdf")
    @SerialName("Kdf")
    val kdfType: Int? = null,
    @JsonNames("kdfIterations")
    @SerialName("KdfIterations")
    val kdfIterationsCount: Int? = null,
    @JsonNames("kdfMemory")
    @SerialName("KdfMemory")
    val kdfMemory: Int? = null,
    @JsonNames("kdfParallelism")
    @SerialName("KdfParallelism")
    val kdfParallelism: Int? = null,
    @JsonNames("kdfSettings")
    @SerialName("KdfSettings")
    val kdfSettings: KdfSettings? = null,
    @JsonNames("salt")
    @SerialName("Salt")
    val salt: String? = null,
) {
    @Serializable
    data class KdfSettings(
        @JsonNames("kdfType")
        @SerialName("KdfType")
        val kdfType: Int? = null,
        @JsonNames("iterations")
        @SerialName("Iterations")
        val iterations: Int? = null,
        @JsonNames("memory")
        @SerialName("Memory")
        val memory: Int? = null,
        @JsonNames("parallelism")
        @SerialName("Parallelism")
        val parallelism: Int? = null,
    )
}

private const val PBKDF2_ITERATIONS = 600000

private const val ARGON2_ITERATIONS = 3
private const val ARGON2_MEMORY_MB = 64
private const val ARGON2_PARALLELISM = 4

fun AccountPreLoginResponse.toDomain(email: String): PreLogin {
    val resolvedSalt = salt
        ?.takeIf { it.isNotBlank() }
        ?: email

    val resolvedKdfType = kdfSettings?.kdfType ?: kdfType
    val resolvedKdfIterationsCount = kdfSettings?.iterations ?: kdfIterationsCount
    val resolvedKdfMemory = kdfSettings?.memory ?: kdfMemory
    val resolvedKdfParallelism = kdfSettings?.parallelism ?: kdfParallelism

    val hash = when (resolvedKdfType) {
        // is AccountPreLoginResponse.Pbkdf2
        null, 0 -> {
            PreLogin.Hash.Pbkdf2(
                iterationsCount = resolvedKdfIterationsCount
                    .takeOrDefault(PBKDF2_ITERATIONS),
            )
        }
        // is AccountPreLoginResponse.Argon2id
        1 -> {
            PreLogin.Hash.Argon2id(
                iterationsCount = resolvedKdfIterationsCount
                    .takeOrDefault(ARGON2_ITERATIONS),
                memoryMb = resolvedKdfMemory
                    .takeOrDefault(ARGON2_MEMORY_MB),
                parallelism = resolvedKdfParallelism
                    .takeOrDefault(ARGON2_PARALLELISM),
            )
        }

        else -> throw IllegalArgumentException("KDF type $resolvedKdfType is not supported!")
    }
    return PreLogin(
        hash = hash,
        salt = resolvedSalt,
    )
}

private fun Int?.takeOrDefault(default: Int) = this
    ?.takeIf { it != 0 }
    ?: default
