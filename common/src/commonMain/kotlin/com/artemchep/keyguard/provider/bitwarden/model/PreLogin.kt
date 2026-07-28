package com.artemchep.keyguard.provider.bitwarden.model

import com.artemchep.keyguard.util.foundation.crypto.KdfLimits

private const val BYTES_PER_MEBIBYTE = 1_048_576UL

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
                require(iterationsCount <= ITERATIONS_MAX) {
                    preloginTooLargeMessage("PBKDF2 iterations", ITERATIONS_MAX.toString())
                }
            }

            companion object {
                const val ITERATIONS_MIN = 600_000

                // Upper bound guards against a malicious or misconfigured server
                // forcing an unbounded key-derivation cost before login.
                const val ITERATIONS_MAX = 2_000_000
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
                require(iterationsCount <= ITERATIONS_MAX) {
                    preloginTooLargeMessage("Argon2 iterations", ITERATIONS_MAX.toString())
                }
                require(memoryMb >= MEMORY_MB_MIN) {
                    preloginDowngradeMessage("Argon2 memory", "$MEMORY_MB_MIN MiB")
                }
                require(memoryMb <= MEMORY_MB_MAX) {
                    preloginTooLargeMessage("Argon2 memory", "$MEMORY_MB_MAX MiB")
                }
                require(parallelism >= PARALLELISM_MIN) {
                    preloginDowngradeMessage("Argon2 parallelism", PARALLELISM_MIN.toString())
                }
                require(parallelism <= PARALLELISM_MAX) {
                    preloginTooLargeMessage("Argon2 parallelism", PARALLELISM_MAX.toString())
                }
            }

            companion object {
                const val ITERATIONS_MIN = 2
                const val MEMORY_MB_MIN = 16
                const val PARALLELISM_MIN = 1

                // Upper bounds guard against a malicious or misconfigured server
                // forcing an unbounded / OOM key-derivation cost before login.
                // Shared with the KDBX file path via [KdfLimits] so both
                // untrusted-input surfaces enforce one source of truth.
                val ITERATIONS_MAX: Int = KdfLimits.MaxArgon2Iterations.toInt()
                val MEMORY_MB_MAX: Int = (KdfLimits.MaxArgon2Memory / BYTES_PER_MEBIBYTE).toInt()
                val PARALLELISM_MAX: Int = KdfLimits.MaxArgon2Parallelism.toInt()
            }
        }
    }
}

private fun preloginDowngradeMessage(
    name: String,
    min: String,
) = "$name must be at least $min; contact the server admin."

private fun preloginTooLargeMessage(
    name: String,
    max: String,
) = "$name must be at most $max; contact the server admin."
