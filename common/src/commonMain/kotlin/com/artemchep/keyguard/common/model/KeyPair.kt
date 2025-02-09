package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

interface KeyParameterRawZero {
    val type: KeyPair.Type
    val privateKey: KeyParameterRaw
    val publicKey: KeyParameterRaw

    interface KeyParameterRaw {
        val encoded: ByteArray
    }
}

data class KeyPairRaw(
    override val type: KeyPair.Type,
    override val privateKey: KeyParameter,
    override val publicKey: KeyParameter,
) : KeyParameterRawZero {
    data class KeyParameter(
        override val encoded: ByteArray,
    ) : KeyParameterRawZero.KeyParameterRaw {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as KeyParameter

            return encoded.contentEquals(other.encoded)
        }

        override fun hashCode(): Int {
            return encoded.contentHashCode()
        }
    }
}

data class KeyPairDecor(
    val privateKey: String,
    val publicKey: String,
    val fingerprint: String,
)

data class KeyPair(
    override val type: Type,
    override val privateKey: KeyParameter,
    override val publicKey: KeyParameter,
) : KeyParameterRawZero {
    // Sorted by the priority
    enum class Type(
        val key: String,
        val title: String,
        val shortDescription: TextHolder,
    ) {
        ED25519(
            key = "ed25519",
            title = "Ed25519",
            shortDescription = TextHolder.Res(Res.string.generator_key_ed25519_text),
        ),
        RSA(
            key = "rsa",
            title = "RSA",
            shortDescription = TextHolder.Res(Res.string.generator_key_rsa_text),
        );

        companion object {
            val default get() = entries.first()

            fun getOrDefault(
                key: String?,
                default: Type = this.default,
            ): Type = Type.entries.firstOrNull { it.key == key }
                ?: default
        }
    }

    data class KeyParameter(
        override val encoded: ByteArray,
        val type: Type,
        val ssh: String,
        val fingerprint: String,
    ) : KeyParameterRawZero.KeyParameterRaw {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as KeyParameter

            if (!encoded.contentEquals(other.encoded)) return false
            if (type != other.type) return false
            if (ssh != other.ssh) return false
            if (fingerprint != other.fingerprint) return false

            return true
        }

        override fun hashCode(): Int {
            var result = encoded.contentHashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + ssh.hashCode()
            result = 31 * result + fingerprint.hashCode()
            return result
        }
    }

    fun toTrimModel(): KeyParameterRawZero = this
}
