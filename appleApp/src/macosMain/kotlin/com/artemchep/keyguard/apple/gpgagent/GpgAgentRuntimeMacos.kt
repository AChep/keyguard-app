package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.apple.agent.AgentHelperIpcServer
import com.artemchep.keyguard.apple.agent.AgentHelperRuntime
import com.artemchep.keyguard.apple.agent.AgentIpcListener
import com.artemchep.keyguard.apple.agent.readPacket
import com.artemchep.keyguard.apple.agent.writePacket
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPacketSession
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
            "--lifecycle-lock-dir", locks,
        )
    }

    override suspend fun start(scope: CoroutineScope, config: GpgAgentRuntimeConfig): GpgAgentRuntimeHandle {
        require(config.authToken.size == GpgAgentPacketSession.AUTH_TOKEN_SIZE)
        val handle = helper.start(
            scope = scope,
            authToken = config.authToken,
            server = { ipcSocket, token, childPid -> MacosGpgIpcServer(ipcSocket, config, token, childPid) },
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

internal class MacosGpgIpcServer(
    path: String,
    private val config: GpgAgentRuntimeConfig,
    authToken: ByteArray,
    private val childPid: () -> Int?,
) : AgentHelperIpcServer {
    private val listener = AgentIpcListener(path, MAX_CONNECTIONS)
    private val token = authToken.copyOf()

    val path: String get() = listener.path

    override suspend fun serve() = listener.serve(
        childPid = childPid,
        onConnectionFailure = { config.log("GPG IPC connection closed") },
        handle = ::handle,
    )

    private suspend fun handle(client: Int) {
        val session = GpgAgentPacketSession(config.processor, token)
        try {
            while (true) {
                val packet = withTimeoutOrNull(IO_TIMEOUT_MS) {
                    readPacket(client, GpgAgentPacketSession.MAX_PACKET_SIZE)
                } ?: return
                val reply = try { session.process(packet) } finally { packet.fill(0) }
                try {
                    if (withTimeoutOrNull(IO_TIMEOUT_MS) {
                        writePacket(client, reply.packet, GpgAgentPacketSession.MAX_PACKET_SIZE)
                    } != true || reply.close) return
                } finally {
                    reply.packet.fill(0)
                }
            }
        } finally {
            session.close()
        }
    }

    override fun close() {
        listener.close()
        token.fill(0)
    }
}
