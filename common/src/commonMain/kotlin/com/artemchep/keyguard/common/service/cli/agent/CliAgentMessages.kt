@file:OptIn(ExperimentalSerializationApi::class)

package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf

/**
 * The private channel between Keyguard and its CLI agent. Mirrors
 * `desktopCliAgent/proto/cli_agent.proto`, with the handshake of
 * [AgentIpcMessages]; the shared encodings in `desktopCliAgent/proto/fixtures/`
 * pin both sides.
 *
 * Fields default to their proto3 defaults, so an omitted field decodes and
 * a default value is not encoded, as with the Rust side. Each `oneof` is a
 * sealed type with a class per case; a case has no default, so that it is
 * always encoded.
 */
object CliAgentMessages {
    /** A message without fields. */
    @Serializable
    data object Empty

    @Serializable
    data class Request(
        @ProtoOneOf
        val request: Kind,
    ) {
        @Serializable
        sealed interface Kind {
            @Serializable
            @SerialName("authenticate")
            data class Authenticate(@ProtoNumber(1) val value: AgentIpcMessages.AuthenticateRequest) : Kind

            @Serializable
            @SerialName("manage")
            data class Manage(@ProtoNumber(2) val value: ManageRequest) : Kind

            @Serializable
            @SerialName("mounts")
            data class Mounts(@ProtoNumber(3) val value: Empty) : Kind

            @Serializable
            @SerialName("watch_mounts")
            data class WatchMounts(@ProtoNumber(4) val value: WatchMountsRequest) : Kind

            @Serializable
            @SerialName("mount_status")
            data class MountStatus(@ProtoNumber(5) val value: MountStatusRequest) : Kind

            @Serializable
            @SerialName("resolve_mount")
            data class ResolveMount(@ProtoNumber(6) val value: ResolveMountRequest) : Kind

            @Serializable
            @SerialName("cli")
            data class Cli(@ProtoNumber(7) val value: CliRequest) : Kind
        }
    }

    @Serializable
    data class Response(
        @ProtoOneOf
        val response: Kind,
    ) {
        @Serializable
        sealed interface Kind {
            @Serializable
            @SerialName("authenticate")
            data class Authenticate(@ProtoNumber(1) val value: AgentIpcMessages.AuthenticateResponse) : Kind

            @Serializable
            @SerialName("manage")
            data class Manage(@ProtoNumber(2) val value: ManageResponse) : Kind

            /** Answers both `mounts` and `watch_mounts`. */
            @Serializable
            @SerialName("mounts")
            data class Mounts(@ProtoNumber(3) val value: MountsResponse) : Kind

            @Serializable
            @SerialName("mount_status")
            data class MountStatus(@ProtoNumber(5) val value: Empty) : Kind

            @Serializable
            @SerialName("resolve_mount")
            data class ResolveMount(@ProtoNumber(6) val value: ResolveMountResponse) : Kind

            @Serializable
            @SerialName("cli")
            data class Cli(@ProtoNumber(7) val value: CliResponse) : Kind

            @Serializable
            @SerialName("error")
            data class Error(@ProtoNumber(15) val value: ErrorResponse) : Kind
        }
    }

    /** A stable, non-sensitive [code], such as `user_denied`. */
    @Serializable
    data class ErrorResponse(
        @ProtoNumber(1)
        val code: String = "",
    )

    /** Environment management of an MCP client. Never answered with values. */
    @Serializable
    class ManageRequest(
        @ProtoNumber(1)
        val caller: CallerIdentity? = null,
        /**
         * A fresh random identity of the public connection. An MCP client
         * keeps its approvals for as long as it keeps the connection.
         */
        @ProtoNumber(2)
        val connectionId: ByteArray = byteArrayOf(),
        /** The name the MCP client reports for itself. A hint, never an identity. */
        @ProtoNumber(3)
        val clientName: String = "",
        @ProtoNumber(4)
        val tool: String = "",
        /** The text arguments of the [tool], by name. */
        @ProtoNumber(5)
        val arguments: Map<String, String> = emptyMap(),
        /** The `variables` argument of `append_variables`. */
        @ProtoNumber(6)
        val variables: List<VariableUpdate> = emptyList(),
    )

    @Serializable
    class VariableUpdate(
        @ProtoNumber(1)
        val name: String = "",
        /** `null` to keep the current value, or to create an unset variable. */
        @ProtoNumber(2)
        val value: ByteArray? = null,
    )

    @Serializable
    data class ManageResponse(
        @ProtoOneOf
        val result: Result,
    ) {
        @Serializable
        sealed interface Result {
            /** `authenticate` */
            @Serializable
            @SerialName("accounts")
            data class Accounts(@ProtoNumber(1) val value: AccountList) : Result

            /** `list_environments` */
            @Serializable
            @SerialName("environments")
            data class Environments(@ProtoNumber(2) val value: EnvironmentList) : Result

            /** `create_environment`, `rename_environment` and `append_variables` */
            @Serializable
            @SerialName("environment")
            data class Environment(@ProtoNumber(3) val value: EnvironmentId) : Result

            /** `list_variables` */
            @Serializable
            @SerialName("variables")
            data class Variables(@ProtoNumber(4) val value: VariableList) : Result

            /** `create_local_env_file` */
            @Serializable
            @SerialName("local_env_file")
            data class LocalEnvFile(@ProtoNumber(5) val value: CliAgentMessages.LocalEnvFile) : Result

            /** `list_local_env_files` */
            @Serializable
            @SerialName("local_env_files")
            data class LocalEnvFiles(@ProtoNumber(6) val value: LocalEnvFileList) : Result
        }
    }

    @Serializable
    data class AccountList(
        @ProtoNumber(1)
        val accounts: List<Account> = emptyList(),
    )

    @Serializable
    data class Account(
        @ProtoNumber(1)
        val id: String = "",
        @ProtoNumber(2)
        val name: String = "",
    )

    @Serializable
    data class EnvironmentList(
        @ProtoNumber(1)
        val environments: List<Environment> = emptyList(),
    )

    @Serializable
    data class Environment(
        @ProtoNumber(1)
        val id: String = "",
        @ProtoNumber(2)
        val accountId: String = "",
        @ProtoNumber(3)
        val name: String = "",
        @ProtoNumber(4)
        val variableCount: Int = 0,
        @ProtoNumber(5)
        val editable: Boolean = false,
    )

    @Serializable
    data class EnvironmentId(
        @ProtoNumber(1)
        val id: String = "",
        @ProtoNumber(2)
        val accountId: String = "",
    )

    @Serializable
    data class VariableList(
        @ProtoNumber(1)
        val variables: List<Variable> = emptyList(),
    )

    /** How a variable is sourced, never its value or the item it references. */
    @Serializable
    data class Variable(
        @ProtoNumber(1)
        val name: String = "",
        @ProtoNumber(2)
        val source: VariableSource = VariableSource.UNSPECIFIED,
        @ProtoNumber(3)
        val isSet: Boolean = false,
        @ProtoNumber(4)
        val supported: Boolean = false,
    )

    @Serializable
    enum class VariableSource {
        @ProtoNumber(0)
        UNSPECIFIED,

        @ProtoNumber(1)
        VALUE,

        @ProtoNumber(2)
        REFERENCE,

        @ProtoNumber(3)
        UNSET,

        @ProtoNumber(4)
        UNSUPPORTED,
    }

    @Serializable
    data class LocalEnvFileList(
        @ProtoNumber(1)
        val localEnvFiles: List<LocalEnvFile> = emptyList(),
    )

    @Serializable
    data class LocalEnvFile(
        @ProtoNumber(1)
        val id: String = "",
        @ProtoNumber(2)
        val environmentId: String = "",
        @ProtoNumber(3)
        val path: String = "",
        @ProtoNumber(4)
        val enabled: Boolean = false,
        @ProtoNumber(5)
        val status: MountStatus = MountStatus.UNSPECIFIED,
        /** Absent when the mount's environment is no longer available. */
        @ProtoNumber(6)
        val accountId: String? = null,
    )

    @Serializable
    enum class MountStatus {
        @ProtoNumber(0)
        UNSPECIFIED,

        @ProtoNumber(1)
        PENDING,

        @ProtoNumber(2)
        READY,

        @ProtoNumber(3)
        ERROR,
    }

    /** Answered once the served mounts change from [revision], or after a timeout. */
    @Serializable
    data class WatchMountsRequest(
        @ProtoNumber(1)
        val revision: String = "",
    )

    @Serializable
    data class MountsResponse(
        /** An opaque revision of these mounts. */
        @ProtoNumber(1)
        val revision: String = "",
        @ProtoNumber(2)
        val mounts: List<Mount> = emptyList(),
    )

    @Serializable
    class Mount(
        @ProtoNumber(1)
        val id: String = "",
        @ProtoNumber(2)
        val path: String = "",
        /** Changes whenever the reader of the file must be approved again. */
        @ProtoNumber(3)
        val generation: String = "",
        /**
         * An implicit security-scoped bookmark of the directory of the file on
         * macOS. `null` when the agent opens the directory by its path.
         */
        @ProtoNumber(4)
        val bookmark: ByteArray? = null,
    )

    @Serializable
    data class MountStatusRequest(
        @ProtoNumber(1)
        val mountId: String = "",
        @ProtoNumber(2)
        val status: MountStatus = MountStatus.UNSPECIFIED,
    )

    /** The content of a local `.env` file, for a reader the user approves. */
    @Serializable
    data class ResolveMountRequest(
        @ProtoNumber(1)
        val mountId: String = "",
        @ProtoNumber(2)
        val generation: String = "",
    )

    @Serializable
    class ResolveMountResponse(
        @ProtoNumber(1)
        val generation: String = "",
        @ProtoNumber(2)
        val content: ByteArray = byteArrayOf(),
    )

    /**
     * A command of the `keyguard` CLI: the [request] of the [command], as
     * JSON. The agent validated it; it holds only the fields the command
     * knows.
     */
    @Serializable
    class CliRequest(
        @ProtoNumber(1)
        val caller: CallerIdentity? = null,
        @ProtoNumber(2)
        val command: String = "",
        /** Can hold values. */
        @ProtoNumber(3)
        val request: ByteArray = byteArrayOf(),
    )

    /**
     * The public success body of a command, `{"ok":true,...}`, as JSON. The
     * agent checks it against the command before passing it on.
     */
    @Serializable
    class CliResponse(
        /** Can hold values. */
        @ProtoNumber(1)
        val result: ByteArray = byteArrayOf(),
    )
}
