package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator

object KeyPairGeneratorIos : KeyPairGenerator {
    override fun rsa(
        length: KeyPairGenerator.RsaLength,
    ): KeyParameterRawZero = throw unsupportedCrypto()

    override fun ed25519(): KeyParameterRawZero = throw unsupportedCrypto()

    override fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero = throw unsupportedCrypto()

    override fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair = throw unsupportedCrypto()

    override fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int? = null

    override fun getPrivateKeyLengthOrNull(
        privateKey: String,
    ): Int? = null
}

private fun unsupportedCrypto() = UnsupportedOperationException(
    "Cipher encryption is not supported on iOS yet.",
)
