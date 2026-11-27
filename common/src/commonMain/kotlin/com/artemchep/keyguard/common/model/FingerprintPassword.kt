package com.artemchep.keyguard.common.model

/**
 * What the app password unlocks the vault with.
 */
sealed interface FingerprintPassword {
    /**
     * The key derivation the app password goes through.
     */
    val kdf: MasterKdf

    /**
     * The salt of the key derivation, without any header.
     */
    val salt: MasterPasswordSalt

    /**
     * The version of the format, also the label of the master key.
     */
    val version: MasterKdfVersion

    /**
     * The format of versions 0 and 1: the master key is derived from
     * the password, and the [hash] checks the password.
     *
     * @property kdf [MasterKdf.Pbkdf2Legacy] for version 0, [MasterKdf.Argon2id.V1]
     *   for version 1, or [MasterKdf.Unsupported].
     */
    data class Derived(
        override val kdf: MasterKdf,
        val hash: MasterPasswordHash,
        override val salt: MasterPasswordSalt,
    ) : FingerprintPassword {
        companion object {
            /**
             * The version of the derived format that
             * uses the [kdf].
             */
            fun versionOf(kdf: MasterKdf): MasterKdfVersion = when (kdf) {
                is MasterKdf.Pbkdf2Legacy -> MasterKdfVersion.V0
                // Only the parameters of version 1 exist in the derived format:
                // custom parameters exist in the wrapped format only.
                is MasterKdf.Argon2id -> MasterKdfVersion.V1
                is MasterKdf.Unsupported -> kdf.version
            }
        }

        override val version: MasterKdfVersion
            get() = versionOf(kdf)
    }

    /**
     * The format of version 2: the master key is random, and a key
     * derived from the password wraps it. A change of the password or
     * of the key derivation re-wraps the same master key.
     *
     * @property kdf a [MasterKdf.Argon2id], or [MasterKdf.Unsupported] if its
     *   parameters could not be read: then the [salt] keeps the stored bytes.
     * @property wrappedKey the authenticated wrap of the master key.
     */
    class Wrapped(
        override val kdf: MasterKdf,
        override val salt: MasterPasswordSalt,
        val wrappedKey: ByteArray,
    ) : FingerprintPassword {
        override val version: MasterKdfVersion
            get() = MasterKdfVersion.V2

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Wrapped) return false

            if (kdf != other.kdf) return false
            if (salt != other.salt) return false
            if (!wrappedKey.contentEquals(other.wrappedKey)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = kdf.hashCode()
            result = 31 * result + salt.hashCode()
            result = 31 * result + wrappedKey.contentHashCode()
            return result
        }
    }
}
