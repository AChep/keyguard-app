package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeneratedGpgKey(
    @SerialName("privateKeyArmored")
    val privateKeyArmored: String,
    @SerialName("publicKeyArmored")
    val publicKeyArmored: String,
    @SerialName("fingerprint")
    val fingerprint: String,
    @SerialName("metadata")
    val metadata: GpgAgentKeyMetadata,
    @SerialName("userId")
    val userId: String,
    @SerialName("typeLabel")
    val typeLabel: String,
)

sealed interface GpgKeyConfig {
    val userId: String
    val type: Type

    data class Modern(
        override val userId: String,
    ) : GpgKeyConfig {
        override val type: Type
            get() = Type.MODERN
    }

    data class Rsa(
        override val userId: String,
        val length: RsaLength = RsaLength.default,
    ) : GpgKeyConfig {
        override val type: Type
            get() = Type.RSA
    }

    enum class Type(
        val key: String,
        val title: String,
        val shortDescription: TextHolder,
    ) {
        MODERN(
            key = "modern",
            title = "Ed25519 + X25519",
            shortDescription = TextHolder.Res(Res.string.generator_gpg_key_modern_text),
        ),
        RSA(
            key = "rsa",
            title = "RSA",
            shortDescription = TextHolder.Res(Res.string.generator_key_rsa_text),
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
