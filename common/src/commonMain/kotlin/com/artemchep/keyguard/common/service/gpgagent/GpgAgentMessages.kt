package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
object GpgAgentMessages {
    /** Exact IPC contract revision required by the current app and agent. */
    const val PROTOCOL_REVISION = 1

    @Serializable
    data class IpcRequest(
        @ProtoNumber(1)
        val id: Long = 0L,
        @ProtoNumber(2)
        val authenticate: AuthenticateRequest? = null,
        @ProtoNumber(3)
        val listKeys: ListKeysRequest? = null,
        @ProtoNumber(4)
        val signHash: SignHashRequest? = null,
        @ProtoNumber(5)
        val pkdecrypt: PkdecryptRequest? = null,
    )

    @Serializable
    data class IpcResponse(
        @ProtoNumber(1)
        val id: Long = 0L,
        @ProtoNumber(2)
        val authenticate: AuthenticateResponse? = null,
        @ProtoNumber(3)
        val listKeys: ListKeysResponse? = null,
        @ProtoNumber(4)
        val signHash: SignHashResponse? = null,
        @ProtoNumber(5)
        val pkdecrypt: PkdecryptResponse? = null,
        @ProtoNumber(15)
        val error: ErrorResponse? = null,
    )

    @Serializable
    data class AuthenticateRequest(
        @ProtoNumber(1)
        val token: ByteArray = byteArrayOf(),
        // Keep the wire default at zero so an absent field from a stale agent
        // cannot be mistaken for the current clean-cutover revision.
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
        @ProtoNumber(2)
        val protocolRevision: Int = 0,
    )

    @Serializable
    data class CallerIdentity(
        @ProtoNumber(1)
        override val pid: Int = 0,
        @ProtoNumber(2)
        val uid: Int = 0,
        @ProtoNumber(3)
        val gid: Int = 0,
        @ProtoNumber(4)
        override val processName: String = "",
        @ProtoNumber(5)
        override val executablePath: String = "",
        @ProtoNumber(6)
        val appPid: Int = 0,
        @ProtoNumber(7)
        override val appName: String = "",
        @ProtoNumber(8)
        override val appBundlePath: String = "",
        /**
         * Machine-verifiable authorization evidence. All preceding fields are
         * display metadata and are deliberately excluded from approval keys.
         */
        @ProtoNumber(9)
        override val authorization: CallerAuthorization? = null,
    ) : AgentCallerIdentity

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
        const val UNSUPPORTED = 6
    }
}
