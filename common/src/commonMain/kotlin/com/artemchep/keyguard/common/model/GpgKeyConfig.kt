package com.artemchep.keyguard.common.model

sealed interface GpgKeyConfig {
    val userId: String
    val type: Type
    val expiry: GpgKeyExpiry

    data class Modern(
        override val userId: String,
        override val expiry: GpgKeyExpiry = GpgKeyExpiry.default,
    ) : GpgKeyConfig {
        override val type: Type
            get() = Type.MODERN
    }

    data class Rsa(
        override val userId: String,
        val length: RsaLength = RsaLength.default,
        override val expiry: GpgKeyExpiry = GpgKeyExpiry.default,
    ) : GpgKeyConfig {
        override val type: Type
            get() = Type.RSA
    }

    enum class Type(
        val key: String,
        val title: String,
    ) {
        MODERN(
            key = "modern",
            title = "Ed25519 + X25519",
        ),
        RSA(
            key = "rsa",
            title = "RSA",
        );

        companion object {
            val default get() = MODERN

            fun getOrDefault(
                key: String?,
                default: Type = this.default,
            ): Type = entries.firstOrNull { it.key == key }
                ?: default
        }
    }

    enum class RsaLength(
        val size: Int,
    ) {
        B3072(3072),
        B4096(4096);

        companion object {
            val default get() = B4096

            fun getOrDefault(
                length: String,
                default: RsaLength = this.default,
            ): RsaLength = getOrDefault(
                length = length.toIntOrNull() ?: return default,
                default = default,
            )

            fun getOrDefault(
                length: Int,
                default: RsaLength = this.default,
            ): RsaLength = entries.firstOrNull { it.size == length }
                ?: default
        }
    }
}
