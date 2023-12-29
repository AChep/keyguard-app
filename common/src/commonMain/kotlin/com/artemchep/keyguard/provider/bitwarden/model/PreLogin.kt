package com.artemchep.keyguard.provider.bitwarden.model

data class PreLogin(
    val hash: Hash,
) {
    sealed interface Hash {
        data class Pbkdf2(
            val iterationsCount: Int,
        ) : Hash {
            init {
                require(iterationsCount > 0)
            }
        }

        data class Argon2id(
            val iterationsCount: Int,
            val memoryMb: Int,
            val parallelism: Int,
        ) : Hash {
            init {
                require(iterationsCount > 0)
                require(memoryMb > 0)
            }
        }
    }
}
