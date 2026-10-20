package com.artemchep.keyguard.common.service.cli

import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** Application boundary of the `keyguard` command-line tool, shared by the UI of every platform. */
interface CliService {
    val state: StateFlow<CliServiceState>
    val approvals: StateFlow<List<CliApprovalRequest>>

    suspend fun setEnabled(enabled: Boolean)

    /**
     * Confirms the user's identity for the pending prompt [id] with [verify],
     * if its operation requires it. Returns `false` if the user did not
     * confirm it, or if the prompt is no longer pending.
     */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean

    /** @return `true` if the prompt was still pending. */
    fun respondToApproval(id: String, approved: Boolean): Boolean
}

data class CliServiceState(
    /** Whether the CLI may receive values. */
    val enabled: Boolean = false,
    /** Path of the bundled `keyguard` CLI; `null` if this build has none. */
    val path: String? = null,
    /** Optional runtime socket override that the CLI must also use. */
    val socketPath: String? = null,
    /** What the platform lets the bundled CLI do. */
    val capabilities: Set<CliPlatformCapability> = emptySet(),
) {
    /** Whether this build can serve a command with the [requirements]. */
    fun supports(requirements: CliCommandRequirements): Boolean =
        path != null && capabilities.containsAll(requirements.platform)

    /** Whether this build serves any command of the CLI. */
    val supported: Boolean
        get() = CliCommand.entries.any { supports(it.requirements) }

    /** Whether the CLI needs the agent that serves it. */
    val needsAgent: Boolean
        get() = enabled && supported
}

/**
 * The process that asked for access. The process and its application are
 * verified by the operating system.
 */
data class CliCaller(
    val pid: Int = 0,
    val processName: String = "",
    val executablePath: String = "",
    val appName: String = "",
    val appBundlePath: String = "",
)

data class CliApprovalRequest(
    val id: String,
    val caller: CliCaller,
    /** Approving also lets this terminal tab repeat the operation until the approval expires. */
    val terminalSession: Boolean,
    val expiresAt: Instant,
    val operation: CliOperation,
)

/** What a command of the CLI asks to do. */
sealed interface CliOperation {
    /**
     * `keyguard run`: starts the [command] with the values of an environment.
     * The command and the directory are reported by the CLI.
     */
    data class Run(
        val environmentId: String,
        val environmentName: String?,
        /** The account that holds the environment. */
        val accountName: String?,
        val command: List<String>,
        val workingDirectory: String,
    ) : CliOperation
}

/** Stable, non-secret error codes; raw exceptions never reach the CLI. */
class CliServiceException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)
