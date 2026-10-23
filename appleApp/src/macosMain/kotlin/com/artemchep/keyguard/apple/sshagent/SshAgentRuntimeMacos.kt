package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.apple.agent.AgentHelperRuntime
import com.artemchep.keyguard.apple.agent.AgentPacketIpcServer
import com.artemchep.keyguard.common.service.sshagent.SshAgentPacketSession
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSBundle

internal actual fun createSshAgentRuntime(): SshAgentRuntime = MacosSshAgentRuntime()

/** Runs the bundled SSH agent, which serves ssh clients at [sshAuthSockPath]
 * in the App Group so that they can reach it from outside the sandbox. */
internal class MacosSshAgentRuntime(
    private val binaryPath: () -> String? = {
        NSBundle.mainBundle.pathForResource("keyguard-ssh-agent", ofType = null)
    },
    private val groupPath: () -> String? = ::appleAppGroupContainerPath,
) : SshAgentRuntime {
    override val sshAuthSockPath: String? get() = groupPath()?.let(::sshAuthSockPath)

    override val isBinaryAvailable: Boolean get() = binaryPath() != null

    private val helper = AgentHelperRuntime(
        name = "SSH agent",
        binaryPath = binaryPath,
        groupPath = groupPath,
        directoryName = "ssh",
        // Users point SSH_AUTH_SOCK at this path, so it stays where it was.
        publicSocket = { group, _ -> sshAuthSockPath(group) },
    ) { publicSocket, _ ->
        listOf("--ssh-socket", publicSocket)
    }

    @Suppress("TooGenericExceptionCaught") // The controller reports any startup failure the same way.
    override suspend fun start(
        scope: CoroutineScope,
        config: SshAgentRuntimeConfig,
    ): SshAgentRuntimeHandle? {
        val handle = try {
            helper.start(
                scope = scope,
                authToken = config.authToken,
                server = { parent, token ->
                    AgentPacketIpcServer(
                        parent = parent,
                        authToken = token,
                        maxConnections = MAX_CONNECTIONS,
                        // The agent keeps one connection across idle periods.
                        ioTimeoutMs = null,
                        onConnectionFailure = { config.log("SSH IPC connection closed") },
                    ) { authToken -> SshAgentPacketSession(config.processor, authToken) }
                },
                onFailure = { e ->
                    // An IPC failure has no message; its cause names the kind.
                    config.log("SSH agent stopped: ${e.message ?: e.cause?.message}")
                    config.onTerminated()
                },
                onReady = { config.log("SSH agent ready") },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            config.log("Failed to start the SSH agent: ${e.message ?: e.cause?.message}")
            return null
        }
        return object : SshAgentRuntimeHandle {
            override val isRunning: Boolean get() = handle.isRunning
            override val sshAuthSockPath: String get() = handle.publicSocket
            override fun stop() = handle.stop()
        }
    }
}

private const val MAX_CONNECTIONS = 8

private fun sshAuthSockPath(group: String) = group.trimEnd('/') + "/ssh-agent.sock"
