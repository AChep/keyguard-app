package com.artemchep.keyguard.util.foundation.crypto

object KdfLimits {
    /** Maximum number of AES-KDF rounds. */
    const val MaxAesRounds: ULong = 100_000_000UL

    /** Minimum Argon2 memory, in bytes (8 KiB). */
    const val MinArgon2Memory: ULong = 8_192UL

    /** Maximum Argon2 memory, in bytes (1 GiB). */
    const val MaxArgon2Memory: ULong = 1_073_741_824UL

    const val MaxArgon2Iterations: ULong = 10_000UL

    const val MaxArgon2Parallelism: UInt = 64U
}
