package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.util.foundation.crypto.KdfLimits

/**
 * The key derivation that turns the app password into the key that
 * wraps the master key, or, in the derived format, into the master
 * password hash and the master key.
 */
sealed interface MasterKdf {
    /**
     * Legacy PBKDF2-SHA256: 100 000 iterations for the hash,
     * 10 000 iterations for the key.
     */
    data object Pbkdf2Legacy : MasterKdf

    data class Argon2id(
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
    ) : MasterKdf {
        /** The memory, in bytes. */
        val memoryBytes: Long
            get() = memoryKib.toLong() * BYTES_PER_KIB

        companion object {
            /**
             * The parameters of version 1. Frozen: the vaults
             * created with version 1 depend on them.
             */
            val V1 = Argon2id(
                iterations = 3,
                memoryKib = 65536, // 64 MiB
                parallelism = 4,
            )

            /**
             * The initial value of a new key derivation form.
             */
            val DEFAULT = V1

            const val KIB_PER_MIB = 1024

            private const val BYTES_PER_KIB = 1024L

            private const val BYTES_PER_MIB = 1_048_576UL

            // Bounds of the key derivation form.

            const val ITERATIONS_MIN = 2
            const val ITERATIONS_MAX = 100

            const val MEMORY_MIB_MIN = 16

            /** The largest memory the native derivation accepts, 1024 MiB. */
            val MEMORY_MIB_MAX: Int = (KdfLimits.MaxArgon2Memory / BYTES_PER_MIB).toInt()

            const val PARALLELISM_MIN = 1
            const val PARALLELISM_MAX = 16
        }
    }

    /**
     * A derivation this build does not know. It never derives a key, so
     * the password does not unlock the vault.
     */
    data class Unsupported(
        val version: MasterKdfVersion,
    ) : MasterKdf

    companion object {
        /**
         * Returns the derivation of the given [version], if the
         * version alone defines it.
         */
        fun fromVersion(version: MasterKdfVersion): MasterKdf = when (version) {
            MasterKdfVersion.V0 -> Pbkdf2Legacy
            MasterKdfVersion.V1 -> Argon2id.V1
            else -> Unsupported(version)
        }
    }
}
