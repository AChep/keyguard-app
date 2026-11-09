@file:Suppress("MagicNumber") // FIDO/CBOR wire constants and bounded frame sizes.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** FIDO CTAP 2.2 section 11.5. Callers own and must clear returned key buffers. */
public object NativeCryptoHybrid {
    public fun respond(
        peerIdentity: ByteArray,
        psk: ByteArray,
        message: ByteArray,
    ): ByteArray {
        require(psk.size == 32 && message.size <= MAX_FRAME_BYTES)
        val request = HybridHandshakeRequestProto(psk = psk, peerIdentity = peerIdentity, message = message)
        return NativeCrypto
            .call("hybrid_handshake", HybridHandshakeOperationProto(request))
            .requireBytes("hybrid_handshake")
    }

    public fun encrypt(
        key: ByteArray,
        counter: Int,
        data: ByteArray,
    ): ByteArray = traffic(CipherDirectionProto.ENCRYPT, key, counter, data)

    public fun decrypt(
        key: ByteArray,
        counter: Int,
        data: ByteArray,
    ): ByteArray = traffic(CipherDirectionProto.DECRYPT, key, counter, data)

    private fun traffic(
        direction: CipherDirectionProto,
        key: ByteArray,
        counter: Int,
        data: ByteArray,
    ): ByteArray {
        require(key.size == 32)
        require(counter in 0..0xffffff)
        require(data.size <= MAX_FRAME_BYTES)
        val request = HybridTrafficRequestProto(direction = direction, key = key, counter = counter, data = data)
        return NativeCrypto
            .call("hybrid_traffic", HybridTrafficOperationProto(request))
            .requireBytes("hybrid_traffic")
    }
}

private const val MAX_FRAME_BYTES = 65_536

@Serializable
@SerialName("hybrid_handshake")
internal data class HybridHandshakeOperationProto(
    @ProtoNumber(63) val value: HybridHandshakeRequestProto,
) : NativeRequestOperationProto

@Serializable
internal data class HybridHandshakeRequestProto(
    @ProtoNumber(1) val psk: ByteArray,
    @ProtoNumber(2) val peerIdentity: ByteArray,
    @ProtoNumber(3) val message: ByteArray,
)

@Serializable
@SerialName("hybrid_traffic")
internal data class HybridTrafficOperationProto(
    @ProtoNumber(64) val value: HybridTrafficRequestProto,
) : NativeRequestOperationProto

@Serializable
internal data class HybridTrafficRequestProto(
    @ProtoNumber(1) val direction: CipherDirectionProto,
    @ProtoNumber(2) val key: ByteArray,
    @ProtoNumber(3) val counter: Int,
    @ProtoNumber(4) val data: ByteArray,
)
