package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.SshAgentIpcServerApple
import com.artemchep.keyguard.SshAgentManagerApple
import com.artemchep.keyguard.SshAgentRequestProcessorApple
import kotlinx.coroutines.CoroutineScope

internal actual fun createSshAgentRuntime(): SshAgentRuntime = AppleSshAgentRuntime

/**
 * macOS SSH-agent runtime: wires the [SshAgentRequestProcessorApple] →
 * [SshAgentIpcServerApple] → [SshAgentManagerApple] chain that spawns the reused
 * `keyguard-ssh-agent` binary over a POSIX IPC socket.
 */
private object AppleSshAgentRuntime : SshAgentRuntime {
    override val sshAuthSockPath: String
        get() = SshAgentManagerApple.defaultSshAuthSockPath

    override val isBinaryAvailable: Boolean
        get() = SshAgentManagerApple.isBinaryAvailable

    override fun start(
        scope: CoroutineScope,
        config: SshAgentRuntimeConfig,
    ): SshAgentRuntimeHandle? {
        val processor = SshAgentRequestProcessorApple(
            logRepository = config.logRepository,
            getVaultSession = config.getVaultSession,
            getSshAgentApprovalWindow = config.getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = config.getSshAgentApprovalCachePolicy,
            getSshAgentFilter = config.getSshAgentFilter,
            scope = scope,
            sshAgentPublicKeyRepository = config.sshAgentPublicKeyRepository,
            sessionId = config.sessionId,
            onApproval = config.onApproval,
        )
        val server = SshAgentIpcServerApple(
            authToken = config.authToken,
            processor = processor,
            scope = scope,
            log = config.log,
        )
        val manager = SshAgentManagerApple(
            authToken = config.authToken,
            ipcServer = server,
            log = config.log,
        )
        manager.onTerminated = config.onTerminated
        val started = manager.start(scope)
        if (!started) {
            manager.stop()
            return null
        }
        return object : SshAgentRuntimeHandle {
            override val isRunning: Boolean
                get() = manager.isRunning

            override val sshAuthSockPath: String
                get() = manager.sshAuthSockPath

            override fun stop() = manager.stop()
        }
    }
}
