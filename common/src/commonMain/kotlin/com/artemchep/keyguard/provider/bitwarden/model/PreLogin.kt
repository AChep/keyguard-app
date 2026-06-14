package com.artemchep.keyguard.provider.bitwarden.model

data class PreLogin(
    val hash: Hash,
    val salt: String,
) {
    sealed interface Hash {
        data class Pbkdf2(
            val iterationsCount: Int,
        ) : Hash {
            init {
                require(iterationsCount >= ITERATIONS_MIN) {
                    preloginDowngradeMessage("PBKDF2 iterations", ITERATIONS_MIN.toString())
                }
            }

            companion object {
                const val ITERATIONS_MIN = 600_000
            }
        }

        data class Argon2id(
            val iterationsCount: Int,
            val memoryMb: Int,
            val parallelism: Int,
        ) : Hash {
            init {
                require(iterationsCount >= ITERATIONS_MIN) {
                    preloginDowngradeMessage("Argon2 iterations", ITERATIONS_MIN.toString())
                }
                require(memoryMb >= MEMORY_MB_MIN) {
                    preloginDowngradeMessage("Argon2 memory", "$MEMORY_MB_MIN MiB")
                }
                require(parallelism >= PARALLELISM_MIN) {
                    preloginDowngradeMessage("Argon2 parallelism", PARALLELISM_MIN.toString())
                }
            }

            companion object {
                const val ITERATIONS_MIN = 2
                const val MEMORY_MB_MIN = 16
                const val PARALLELISM_MIN = 1
            }
        }
    }
}

private fun preloginDowngradeMessage(
    name: String,
    min: String,
) = "$name must be at least $min; contact the server admin."
