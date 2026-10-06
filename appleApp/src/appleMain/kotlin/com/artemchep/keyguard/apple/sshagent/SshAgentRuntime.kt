package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope

data class SshAgentApprovalInfo(
    val keyName: String,
    val keyFingerprint: String,
    val callerName: String,
    val callerPath: String,
)

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

interface SshAgentRuntimeHandle {
    val isRunning: Boolean
    val sshAuthSockPath: String?
    fun stop()
}

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
