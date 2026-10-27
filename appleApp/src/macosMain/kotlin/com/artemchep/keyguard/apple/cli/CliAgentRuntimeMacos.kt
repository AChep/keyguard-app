package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.apple.agent.AgentHelperIpcServer
import com.artemchep.keyguard.apple.agent.AgentHelperRuntime
import com.artemchep.keyguard.apple.agent.asAgentByteStream
import com.artemchep.keyguard.apple.agent.bindAgentHelperIpcHost
import com.artemchep.keyguard.apple.environments.EnvironmentBookmarkTransfers
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.serveCliAgentConnection
import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.cli.agent.CliAgentPacketSession
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import com.artemchep.keyguard.util.agentipc.AgentIpcConnection
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSBundle

internal actual fun createCliAgentRuntime(): CliAgentRuntime = MacosCliAgentRuntime()

/** Main-application broker runtime. The helper inherits static sandbox rights;
 * project-directory grants are explicitly transferred as implicit bookmarks. */
@OptIn(ExperimentalForeignApi::class)
internal class MacosCliAgentRuntime(
    private val binaryPath: () -> String? = {
        NSBundle.mainBundle.pathForResource("keyguard-cli-agent", ofType = null)
    },
    private val groupPath: () -> String? = ::appleAppGroupContainerPath,
) : CliAgentRuntime {
    override val isBinaryAvailable: Boolean get() = binaryPath() != null
    override val command: String? get() = NSBundle.mainBundle.pathForResource("keyguard", ofType = null)
    override val publicSocket: String? get() = groupPath()?.trimEnd('/')?.plus("/cli/s")
    override val supportsMounts: Boolean get() = true
    override val requiresDirectoryGrant: Boolean get() = true

    // Mac App Store builds must sandbox every bundled executable, and a
    // sandboxed CLI would sandbox the command it starts.
    override val cliCapabilities: Set<CliPlatformCapability>
        get() = if (NSBundle.mainBundle.objectForInfoDictionaryKey("KeyguardDistribution") == "direct") {
            setOf(CliPlatformCapability.Unsandboxed)
        } else {
            emptySet()
        }

    private val helper = AgentHelperRuntime(
        name = "CLI agent",
        binaryPath = binaryPath,
        groupPath = groupPath,
        directoryName = "cli",
        dataDirectoryName = "state",
    ) { socket, stateDirectory ->
        listOf(
            "--cli-socket", socket,
            "--state-dir", requireNotNull(stateDirectory),
        )
    }

    override suspend fun start(
        scope: CoroutineScope,
        host: CliAgentHost,
        authToken: ByteArray,
    ): CliAgentRuntimeHandle {
        require(authToken.size == AgentIpcAuthenticator.TOKEN_SIZE)
        val handle = helper.start(
            scope = scope,
            authToken = authToken,
            server = { parent, token -> MacosCliAgentIpcServer(parent, host, token) },
            onFailure = { host.reportStatus(AgentStatus.Failed) },
        )
        return object : CliAgentRuntimeHandle {
            override suspend fun join() = handle.join()
            override fun stop() = handle.stop()
        }
    }
}

// The CLI client gives each connection 70 seconds.
private const val IO_TIMEOUT_MS = 70_000L

private const val MAX_CONNECTIONS = 32

/** Each connection of the CLI agent authenticates, then makes exactly one request.
 * Input or the end of the stream while it is processed cancels it, and with it
 * any approval it waits for. */
internal class MacosCliAgentIpcServer(
    parent: String,
    host: CliAgentHost,
    authToken: ByteArray,
) : AgentHelperIpcServer {
    private val bookmarks = EnvironmentBookmarkTransfers(host.mounts)
    private val handler = host.createRequestHandler(directoryAccess = bookmarks)
    private val ipc = bindAgentHelperIpcHost(parent, MAX_CONNECTIONS)

    private val lock = SynchronizedObject()

    // Guarded by the lock; zeroed on close.
    private val token = authToken.copyOf()
    private var closed = false

    override val endpoint: String get() = ipc.endpoint

    // Never include packet contents or provider errors in diagnostics.
    override suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
        ipc.serve(expectedPeer, ::serveConnection)
    }

    private suspend fun serveConnection(connection: AgentIpcConnection) {
        val session = createSession()
            ?: return
        try {
            serveCliAgentConnection(
                stream = connection.asAgentByteStream(),
                maxPacketSize = CliAgentPacketSession.MAX_PACKET_SIZE,
                process = session::process,
                ioTimeoutMs = IO_TIMEOUT_MS,
            )
        } finally {
            session.close()
        }
    }

    /**
     * A session with its own copy of the token, or null once the server is
     * closed: a zeroed token must never authenticate anyone.
     */
    private fun createSession(): CliAgentPacketSession? = synchronized(lock) {
        if (closed) null else CliAgentPacketSession(handler, token)
    }

    override fun close() {
        ipc.stop()
        synchronized(lock) {
            closed = true
            token.fill(0)
        }
        bookmarks.close()
    }
}
