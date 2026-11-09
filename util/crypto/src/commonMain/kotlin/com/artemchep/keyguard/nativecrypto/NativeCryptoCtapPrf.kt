@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@file:Suppress("MagicNumber") // CTAP PIN/UV key and salt sizes.

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Caller-owned secret buffers. Clear every returned key/salt buffer after use. */
public object NativeCryptoCtapPrf {
    /** Private scalar (32 bytes), followed by the uncompressed public point (65 bytes). */
    public fun generate(): ByteArray =
        NativeCrypto
            .call("ctap_prf_key_generate", CtapPrfKeyGenerateOperationProto(CtapPrfKeyGenerateRequestProto()))
            .requireBytes("ctap_prf_key_generate")
            .requireSize(setOf(97))

    /** HMAC key (32), AES key (32), and one or two authenticated, decrypted salts. */
    public fun decrypt(
        protocol: Int,
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        saltEnc: ByteArray,
        saltAuth: ByteArray,
    ): ByteArray {
        require(protocol in 1..2 && privateKey.size == 32 && peerPublicKey.size == 65)
        require(saltEnc.size in 32..80 && saltAuth.size in 16..32)
        val request = CtapPrfSaltsOpenRequestProto(protocol, privateKey, peerPublicKey, saltEnc, saltAuth)
        return NativeCrypto
            .call("ctap_prf_salts_open", CtapPrfSaltsOpenOperationProto(request))
            .requireBytes("ctap_prf_salts_open")
            .requireSize(setOf(96, 128))
    }

    public fun encrypt(
        protocol: Int,
        sharedSecret: ByteArray,
        results: ByteArray,
    ): ByteArray {
        require(protocol in 1..2 && sharedSecret.size == 64 && (results.size == 32 || results.size == 64))
        val request = CtapPrfOutputSealRequestProto(protocol, sharedSecret, results)
        return NativeCrypto
            .call("ctap_prf_output_seal", CtapPrfOutputSealOperationProto(request))
            .requireBytes("ctap_prf_output_seal")
    }

    private fun ByteArray.requireSize(sizes: Set<Int>): ByteArray {
        if (size !in sizes) {
            fill(0)
            error("Invalid CTAP PRF result")
        }
        return this
    }
}

@Serializable
@SerialName("ctap_prf_key_generate")
internal data class CtapPrfKeyGenerateOperationProto(
    @ProtoNumber(65) val value: CtapPrfKeyGenerateRequestProto,
) : NativeRequestOperationProto

@Serializable
internal class CtapPrfKeyGenerateRequestProto

@Serializable
@SerialName("ctap_prf_salts_open")
internal data class CtapPrfSaltsOpenOperationProto(
    @ProtoNumber(66) val value: CtapPrfSaltsOpenRequestProto,
) : NativeRequestOperationProto

@Serializable
internal data class CtapPrfSaltsOpenRequestProto(
    @ProtoNumber(1) val protocol: Int,
    @ProtoNumber(2) val privateKey: ByteArray,
    @ProtoNumber(3) val peerPublicKey: ByteArray,
    @ProtoNumber(4) val saltEnc: ByteArray,
    @ProtoNumber(5) val saltAuth: ByteArray,
)

@Serializable
@SerialName("ctap_prf_output_seal")
internal data class CtapPrfOutputSealOperationProto(
    @ProtoNumber(67) val value: CtapPrfOutputSealRequestProto,
) : NativeRequestOperationProto

@Serializable
internal data class CtapPrfOutputSealRequestProto(
    @ProtoNumber(1) val protocol: Int,
    @ProtoNumber(2) val sharedSecret: ByteArray,
    @ProtoNumber(3) val results: ByteArray,
)
