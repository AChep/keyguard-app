package com.artemchep.keyguard.apple.sshagent

import kotlinx.coroutines.CoroutineScope

internal actual fun createSshAgentRuntime(): SshAgentRuntime = NoopSshAgentRuntime

/**
 * iOS has no spawnable SSH agent (process spawning is unavailable), so the
 * runtime is inert and [SshAgentController] reports the agent as unsupported.
 */
private object NoopSshAgentRuntime : SshAgentRuntime {
    override val isBinaryAvailable: Boolean
        get() = false

    override fun start(
        scope: CoroutineScope,
        config: SshAgentRuntimeConfig,
    ): SshAgentRuntimeHandle? = null
}
