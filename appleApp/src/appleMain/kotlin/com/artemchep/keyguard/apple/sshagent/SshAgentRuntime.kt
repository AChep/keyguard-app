package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import kotlinx.coroutines.CoroutineScope

class SshAgentRuntimeConfig(
    val authToken: ByteArray,
    val processor: SshAgentRequestProcessor,
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

    /** Builds and starts the agent and suspends until it is ready; returns a
     * handle, or null if it failed to start. */
    suspend fun start(
        scope: CoroutineScope,
        config: SshAgentRuntimeConfig,
    ): SshAgentRuntimeHandle?
}

internal expect fun createSshAgentRuntime(): SshAgentRuntime
