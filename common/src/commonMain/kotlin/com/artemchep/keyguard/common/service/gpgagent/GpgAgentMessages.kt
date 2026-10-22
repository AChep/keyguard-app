package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentRpcRequest
import com.artemchep.keyguard.common.service.agent.AgentRpcResponse
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The IPC protocol between Keyguard and the GPG agent. Mirrors
 * `commonGpgAgent/proto/gpg_agent.proto`, and its shared messages the
 * `commonAgent/proto/` schemas: [AgentIpcMessages] and [CallerIdentity].
 */
@OptIn(ExperimentalSerializationApi::class)
object GpgAgentMessages {
    @Serializable
    data class IpcRequest(
        @ProtoNumber(1)
        override val id: Long = 0L,
        @ProtoNumber(2)
        override val authenticate: AgentIpcMessages.AuthenticateRequest? = null,
        @ProtoNumber(3)
        val listKeys: ListKeysRequest? = null,
        @ProtoNumber(4)
        val signHash: SignHashRequest? = null,
        @ProtoNumber(5)
        val pkdecrypt: PkdecryptRequest? = null,
    ) : AgentRpcRequest

    @Serializable
    data class IpcResponse(
        @ProtoNumber(1)
        val id: Long = 0L,
        @ProtoNumber(2)
        override val authenticate: AgentIpcMessages.AuthenticateResponse? = null,
        @ProtoNumber(3)
        val listKeys: ListKeysResponse? = null,
        @ProtoNumber(4)
        val signHash: SignHashResponse? = null,
        @ProtoNumber(5)
        val pkdecrypt: PkdecryptResponse? = null,
        @ProtoNumber(15)
        val error: AgentIpcMessages.ErrorResponse? = null,
    ) : AgentRpcResponse

    @Serializable
    data class ListKeysRequest(
        @ProtoNumber(1)
        val caller: CallerIdentity? = null,
    )

    @Serializable
    data class ListKeysResponse(
        @ProtoNumber(1)
        val keys: List<GpgKey> = emptyList(),
    )

    @Serializable
    data class GpgKey(
        @ProtoNumber(1)
        val name: String = "",
        @ProtoNumber(2)
        val keygrip: String = "",
        @ProtoNumber(3)
        val fingerprint: String = "",
        @ProtoNumber(4)
        val algorithm: String = "",
        @ProtoNumber(5)
        val canSign: Boolean = false,
        @ProtoNumber(6)
        val canDecrypt: Boolean = false,
    )

    @Serializable
    data class SignHashRequest(
        @ProtoNumber(1)
        val keygrip: String = "",
        @ProtoNumber(2)
        val hashAlgorithm: String = "",
        @ProtoNumber(3)
        val hash: ByteArray = byteArrayOf(),
        @ProtoNumber(4)
        val caller: CallerIdentity? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SignHashRequest) return false
            return keygrip == other.keygrip &&
                    hashAlgorithm == other.hashAlgorithm &&
                    hash.contentEquals(other.hash) &&
                    caller == other.caller
        }

        override fun hashCode(): Int {
            var result = keygrip.hashCode()
            result = 31 * result + hashAlgorithm.hashCode()
            result = 31 * result + hash.contentHashCode()
            result = 31 * result + (caller?.hashCode() ?: 0)
            return result
        }
    }

    @Serializable
    data class SignHashResponse(
        @ProtoNumber(1)
        val sexp: String = "",
    )

    @Serializable
    data class PkdecryptRequest(
        @ProtoNumber(1)
        val keygrip: String = "",
        @ProtoNumber(2)
        val ciphertext: ByteArray = byteArrayOf(),
        @ProtoNumber(3)
        val caller: CallerIdentity? = null,
        @ProtoNumber(4)
        val unwrapEcdh: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PkdecryptRequest) return false
            return keygrip == other.keygrip &&
                    ciphertext.contentEquals(other.ciphertext) &&
                    caller == other.caller &&
                    unwrapEcdh == other.unwrapEcdh
        }

        override fun hashCode(): Int {
            var result = keygrip.hashCode()
            result = 31 * result + ciphertext.contentHashCode()
            result = 31 * result + (caller?.hashCode() ?: 0)
            result = 31 * result + unwrapEcdh.hashCode()
            return result
        }
    }

    @Serializable
    data class PkdecryptResponse(
        @ProtoNumber(1)
        val valueSexp: String = "",
    )
}
