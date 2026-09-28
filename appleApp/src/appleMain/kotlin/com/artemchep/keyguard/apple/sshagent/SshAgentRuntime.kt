package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope

/** Info shown in the per-sign approval window. */
data class SshAgentApprovalInfo(
    val keyName: String,
    val keyFingerprint: String,
    val callerName: String,
    val callerPath: String,
)

/**
 * Everything the platform SSH-agent runtime needs to spawn and serve the agent.
 * Assembled by [SshAgentController]; the platform implementation
 * ([createSshAgentRuntime]) wires it into the macOS process / IPC machinery.
 */
class SshAgentRuntimeConfig(
    val authToken: ByteArray,
    val sessionId: String,
    val logRepository: LogRepository,
    val getVaultSession: GetVaultSession,
    val getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    val getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy,
    val getSshAgentFilter: GetSshAgentFilter,
    val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
    val onApproval: suspend (SshAgentApprovalInfo) -> Boolean,
    val onTerminated: () -> Unit,
    val log: (String) -> Unit,
)

/** A running SSH agent, returned by [SshAgentRuntime.start]. */
interface SshAgentRuntimeHandle {
    val isRunning: Boolean
    val sshAuthSockPath: String?
    fun stop()
}

/**
 * Platform seam for the SSH agent. The macOS actual spawns the bundled
 * `keyguard-ssh-agent` binary over a POSIX IPC socket; the iOS actual is inert
 * (process spawning is unavailable on iOS), so [SshAgentController] simply
 * reports the agent as unsupported there.
 */
interface SshAgentRuntime {
    /** Configured socket path, available before the agent is enabled. */
    val sshAuthSockPath: String? get() = null

    /** Whether the bundled agent binary exists; cheap, bundle-only lookup. */
    val isBinaryAvailable: Boolean

    /** Builds and starts the agent; returns a handle, or null if it failed to start. */
    fun start(
        scope: CoroutineScope,
        config: SshAgentRuntimeConfig,
    ): SshAgentRuntimeHandle?
}

internal expect fun createSshAgentRuntime(): SshAgentRuntime
