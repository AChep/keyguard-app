package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.model.KeyParameterRawZero

interface KeyPairGenerator {
    enum class RsaLength(
        val size: Int,
    ) {
        B1024(1024),
        B2048(2048),
        B4096(4096);

        companion object {
            inline val default get() = B4096

            fun getOrDefault(
                length: String,
                default: RsaLength = this.default,
            ): RsaLength {
                return getOrDefault(
                    length = length.toIntOrNull()
                        ?: return default,
                    default = default,
                )
            }

            fun getOrDefault(
                length: Int,
                default: RsaLength = this.default,
            ): RsaLength = RsaLength.entries.firstOrNull { it.size == length }
                ?: default
        }
    }

    fun rsa(
        length: RsaLength = RsaLength.B4096,
    ): KeyParameterRawZero

    fun ed25519(
    ): KeyParameterRawZero

    fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero

    fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair

    //
    // Other
    //

    fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int?
}
