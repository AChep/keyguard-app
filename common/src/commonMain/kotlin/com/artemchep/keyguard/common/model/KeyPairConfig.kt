package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator

sealed interface KeyPairConfig {
    val type: KeyPair.Type

    data class Rsa(
        val length: KeyPairGenerator.RsaLength,
    ) : KeyPairConfig {
        override val type: KeyPair.Type
            get() = KeyPair.Type.RSA
    }

    data object Ed25519 : KeyPairConfig {
        override val type: KeyPair.Type
            get() = KeyPair.Type.ED25519
    }
}
