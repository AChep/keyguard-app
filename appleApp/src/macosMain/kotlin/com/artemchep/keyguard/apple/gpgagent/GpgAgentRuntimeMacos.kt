package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.apple.agent.AgentHelperRuntime
import com.artemchep.keyguard.apple.agent.AgentPacketIpcServer
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPacketSession
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSBundle

internal actual fun createGpgAgentRuntime(): GpgAgentRuntime = MacosGpgAgentRuntime()

/** Only the main application controller starts this runtime. It never resolves a
 * fallback socket in the app's private container: external GnuPG cannot reach it. */
@OptIn(ExperimentalForeignApi::class)
internal class MacosGpgAgentRuntime(
    private val binaryPath: () -> String? = {
        NSBundle.mainBundle.pathForResource("keyguard-gpg-agent", ofType = null)
    },
    private val groupPath: () -> String? = ::appleAppGroupContainerPath,
) : GpgAgentRuntime {
    override val isBinaryAvailable: Boolean get() = binaryPath() != null
    // This path belongs to the external user's shell, not NSHomeDirectory() in
    // the sandbox. Setup expands HOME in Terminal and queries its own gpgconf.
    override val gpgHome: String get() = "~/.keyguard/gnupg"
    override val agentSocket: String? get() = groupPath()?.trimEnd('/')?.plus("/gpg/s")

    private val helper = AgentHelperRuntime(
        name = "GPG agent",
        binaryPath = binaryPath,
        groupPath = groupPath,
        directoryName = "gpg",
        dataDirectoryName = "locks",
    ) { publicSocket, locks ->
        listOf(
            "--gpg-socket", publicSocket,
            "--lifecycle-lock-dir", requireNotNull(locks),
        )
    }

    override suspend fun start(scope: CoroutineScope, config: GpgAgentRuntimeConfig): GpgAgentRuntimeHandle {
        require(config.authToken.size == AgentIpcAuthenticator.TOKEN_SIZE)
        val handle = helper.start(
            scope = scope,
            authToken = config.authToken,
            server = { parent, token -> macosGpgIpcServer(parent, config, token) },
            onFailure = { e -> config.onTerminated(e.message) },
            onReady = { config.log("GPG agent ready") },
        )
        return object : GpgAgentRuntimeHandle {
            override val isRunning: Boolean get() = handle.isRunning
            override val gpgHome: String get() = this@MacosGpgAgentRuntime.gpgHome
            override val agentSocket: String get() = handle.publicSocket
            override fun stop() = handle.stop()
        }
    }
}

private const val IO_TIMEOUT_MS = 30_000L

private const val MAX_CONNECTIONS = 8

internal fun macosGpgIpcServer(
    parent: String,
    config: GpgAgentRuntimeConfig,
    authToken: ByteArray,
) = AgentPacketIpcServer(
    parent = parent,
    authToken = authToken,
    maxConnections = MAX_CONNECTIONS,
    ioTimeoutMs = IO_TIMEOUT_MS,
    onConnectionFailure = { config.log("GPG IPC connection closed") },
) { token -> GpgAgentPacketSession(config.processor, token) }
