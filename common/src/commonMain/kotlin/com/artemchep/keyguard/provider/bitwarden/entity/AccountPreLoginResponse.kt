package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class AccountPreLoginResponse(
    @JsonNames("kdf")
    @SerialName("Kdf")
    val kdfType: Int = 0,
    @JsonNames("kdfIterations")
    @SerialName("KdfIterations")
    val kdfIterationsCount: Int = 0,
    @JsonNames("kdfMemory")
    @SerialName("KdfMemory")
    val kdfMemory: Int = 0,
    @JsonNames("kdfParallelism")
    @SerialName("KdfParallelism")
    val kdfParallelism: Int = 0,
)

private const val Pbkdf2Iterations = 600000

private const val Argon2Iterations = 3
private const val Argon2MemoryInMB = 64
private const val Argon2Parallelism = 4

fun AccountPreLoginResponse.toDomain(): PreLogin {
    val hash = when (kdfType) {
        // is AccountPreLoginResponse.Pbkdf2
        0 -> {
            PreLogin.Hash.Pbkdf2(
                iterationsCount = kdfIterationsCount
                    .takeOrDefault(Pbkdf2Iterations),
            )
        }
        // is AccountPreLoginResponse.Argon2id
        1 -> {
            PreLogin.Hash.Argon2id(
                iterationsCount = kdfIterationsCount
                    .takeOrDefault(Argon2Iterations),
                memoryMb = kdfMemory
                    .takeOrDefault(Argon2MemoryInMB),
                parallelism = kdfParallelism
                    .takeOrDefault(Argon2Parallelism),
            )
        }

        else -> throw IllegalArgumentException("KDF type $kdfType is not supported!")
    }
    return PreLogin(
        hash = hash,
    )
}

private fun Int.takeOrDefault(default: Int) = takeIf { it != 0 } ?: default
