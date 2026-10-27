package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentRpcRequest
import com.artemchep.keyguard.common.service.agent.AgentRpcResponse
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Protobuf message definitions for the IPC protocol between Keyguard and the
 * SSH agent bridge.
 *
 * These definitions must match the shared `commonSshAgent/proto/ssh_agent.proto`
 * schema consumed by the Rust SSH-agent binaries, and its shared messages the
 * `commonAgent/proto/` schemas: [AgentIpcMessages] and [CallerIdentity]. We use
 * kotlinx.serialization.protobuf with @ProtoNumber annotations for wire
 * compatibility.
 *
 * Note: Protobuf `oneof` fields are modeled as nullable properties in
 * kotlinx.serialization.protobuf. Only one of the request/response variant
 * fields should be non-null at a time.
 */
@OptIn(ExperimentalSerializationApi::class)
object SshAgentMessages {
    // ================================================================
    // Top-level envelope messages
    // ================================================================

    @Serializable
    data class IpcRequest(
        @ProtoNumber(1)
        override val id: Long = 0L,
        // oneof request: only one should be non-null
        @ProtoNumber(2)
        override val authenticate: AgentIpcMessages.AuthenticateRequest? = null,
        @ProtoNumber(3)
        val listKeys: ListKeysRequest? = null,
        @ProtoNumber(4)
        val signData: SignDataRequest? = null,
    ) : AgentRpcRequest

    @Serializable
    data class IpcResponse(
        @ProtoNumber(1)
        val id: Long = 0L,
        // oneof response: only one should be non-null
        @ProtoNumber(2)
        override val authenticate: AgentIpcMessages.AuthenticateResponse? = null,
        @ProtoNumber(3)
        val listKeys: ListKeysResponse? = null,
        @ProtoNumber(4)
        val signData: SignDataResponse? = null,
        @ProtoNumber(15)
        val error: AgentIpcMessages.ErrorResponse? = null,
    ) : AgentRpcResponse

    // ================================================================
    // Key listing
    // ================================================================

    @Serializable
    data class ListKeysRequest(
        @ProtoNumber(1)
        val caller: CallerIdentity? = null,
    )

    @Serializable
    data class ListKeysResponse(
        @ProtoNumber(1)
        val keys: List<SshKey> = emptyList(),
    )

    @Serializable
    data class SshKey(
        @ProtoNumber(1)
        val name: String = "",
        @ProtoNumber(2)
        val publicKey: String = "",
        @ProtoNumber(3)
        val keyType: String = "",
        @ProtoNumber(4)
        val fingerprint: String = "",
    )

    // ================================================================
    // Signing
    // ================================================================

    @Serializable
    data class SignDataRequest(
        @ProtoNumber(1)
        val publicKey: String = "",
        @ProtoNumber(2)
        val data: ByteArray = byteArrayOf(),
        @ProtoNumber(3)
        val flags: Int = 0,
        @ProtoNumber(4)
        val caller: CallerIdentity? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SignDataRequest) return false
            return publicKey == other.publicKey &&
                    data.contentEquals(other.data) &&
                    flags == other.flags &&
                    caller == other.caller
        }

        override fun hashCode(): Int {
            var result = publicKey.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + flags
            result = 31 * result + (caller?.hashCode() ?: 0)
            return result
        }
    }

    @Serializable
    data class SignDataResponse(
        @ProtoNumber(1)
        val signature: ByteArray = byteArrayOf(),
        @ProtoNumber(2)
        val algorithm: String = "",
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SignDataResponse) return false
            return signature.contentEquals(other.signature) &&
                    algorithm == other.algorithm
        }

        override fun hashCode(): Int {
            var result = signature.contentHashCode()
            result = 31 * result + algorithm.hashCode()
            return result
        }
    }
}
