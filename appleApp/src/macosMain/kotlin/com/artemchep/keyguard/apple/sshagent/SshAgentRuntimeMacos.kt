package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.SshAgentIpcServerApple
import com.artemchep.keyguard.SshAgentManagerApple
import kotlinx.coroutines.CoroutineScope

internal actual fun createSshAgentRuntime(): SshAgentRuntime = AppleSshAgentRuntime

private object AppleSshAgentRuntime : SshAgentRuntime {
    override val sshAuthSockPath: String
        get() = SshAgentManagerApple.defaultSshAuthSockPath

    override val isBinaryAvailable: Boolean
        get() = SshAgentManagerApple.isBinaryAvailable

    override fun start(
        scope: CoroutineScope,
        config: SshAgentRuntimeConfig,
    ): SshAgentRuntimeHandle? {
        val server = SshAgentIpcServerApple(
            authToken = config.authToken,
            processor = config.processor,
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
