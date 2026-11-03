package com.artemchep.keyguard.common.service.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The handshake and error messages shared by the private channels of the
 * SSH, GPG and CLI agents. Mirrors `commonAgent/proto/agent.proto`; each
 * agent's envelope holds them at its own field numbers, and the fixtures of
 * each channel pin both sides.
 */
@OptIn(ExperimentalSerializationApi::class)
object AgentIpcMessages {
    /** The exact revision of the private channels that an agent must speak. */
    const val PROTOCOL_REVISION = 1

    /** The first request of every private connection. */
    @Serializable
    data class AuthenticateRequest(
        /** The 32 bytes the app passed to the agent at launch. */
        @ProtoNumber(1)
        val token: ByteArray = byteArrayOf(),
        // Keep the wire default at zero so an absent field from a stale agent
        // cannot be mistaken for the current revision.
        @ProtoNumber(2)
        val protocolRevision: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is AuthenticateRequest &&
                token.contentEquals(other.token) &&
                protocolRevision == other.protocolRevision

        override fun hashCode(): Int = 31 * token.contentHashCode() + protocolRevision
    }

    @Serializable
    data class AuthenticateResponse(
        @ProtoNumber(1)
        val success: Boolean = false,
        /** The revision of the app, so that an agent can report a mismatch. */
        @ProtoNumber(2)
        val protocolRevision: Int = 0,
    )

    /** An error of the SSH and GPG agents. The CLI agent has string codes instead. */
    @Serializable
    data class ErrorResponse(
        @ProtoNumber(1)
        val message: String = "",
        @ProtoNumber(2)
        val code: Int = ErrorCode.UNSPECIFIED,
    )

    object ErrorCode {
        const val UNSPECIFIED = 0
        const val VAULT_LOCKED = 1
        const val USER_DENIED = 2
        const val KEY_NOT_FOUND = 3
        const val AUTH_FAILED = 4
        const val NOT_AUTHENTICATED = 5

        /** Only the GPG agent answers with this one. */
        const val UNSUPPORTED = 6
    }
}
