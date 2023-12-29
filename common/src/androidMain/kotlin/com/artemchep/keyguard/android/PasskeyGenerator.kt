package com.artemchep.keyguard.android

import com.artemchep.keyguard.provider.bitwarden.CreatePasskeyPubKeyCredParams
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

abstract class PasskeyGeneratorBase : PasskeyGenerator {
    // https://www.iana.org/assignments/cose/cose.xhtml#algorithms
    abstract val type: Int

    /**
     * Returns `true` if the generator can handle given
     * crypto parameters, `false` otherwise.
     */
    override fun handles(
        params: CreatePasskeyPubKeyCredParams,
    ): Boolean = params.alg == type
}

interface PasskeyGenerator {
    /**
     * Returns `true` if the generator can handle given
     * crypto parameters, `false` otherwise.
     */
    fun handles(
        params: CreatePasskeyPubKeyCredParams,
    ): Boolean

    fun keyPair(): KeyPair

    fun sign(
        key: PrivateKey,
        data: ByteArray,
    ): ByteArray
}

class PasskeyGeneratorES256 : PasskeyGeneratorBase() {
    /** ECDSA w/ SHA-256 **/
    override val type: Int = -7

    override fun keyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            val spec = ECGenParameterSpec("secp256r1")
            initialize(spec)
        }
        val key = generator.genKeyPair()
        return key
    }

    override fun sign(
        key: PrivateKey,
        data: ByteArray,
    ): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(key)
        sig.update(data)
        return sig.sign()
    }
}
