package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.apple.agent.AgentHelperIpcServer
import com.artemchep.keyguard.apple.agent.AgentHelperRuntime
import com.artemchep.keyguard.apple.agent.AgentIpcListener
import com.artemchep.keyguard.apple.agent.awaitReadable
import com.artemchep.keyguard.apple.agent.readPacket
import com.artemchep.keyguard.apple.agent.writePacket
import com.artemchep.keyguard.apple.environments.EnvironmentBookmarkTransfers
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.cli.agent.CliAgentPacketReply
import com.artemchep.keyguard.common.service.cli.agent.CliAgentPacketSession
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.MSG_DONTWAIT
import platform.posix.MSG_PEEK
import platform.posix.errno
import platform.posix.recv

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
            "--state-dir", stateDirectory,
        )
    }

    override suspend fun start(
        scope: CoroutineScope,
        host: CliAgentHost,
        authToken: ByteArray,
    ): CliAgentRuntimeHandle {
        require(authToken.size == AUTH_TOKEN_SIZE)
        val handle = helper.start(
            scope = scope,
            authToken = authToken,
            server = { ipcSocket, token, childPid -> MacosCliAgentIpcServer(ipcSocket, host, token, childPid) },
            onFailure = { host.reportStatus(AgentStatus.Failed) },
        )
        return object : CliAgentRuntimeHandle {
            override suspend fun join() = handle.join()
            override fun stop() = handle.stop()
        }
    }
}

private const val AUTH_TOKEN_SIZE = 32

private const val IO_TIMEOUT_MS = 70_000L

private const val MAX_CONNECTIONS = 32

internal class MacosCliAgentIpcServer(
    path: String,
    private val host: CliAgentHost,
    authToken: ByteArray,
    private val childPid: () -> Int?,
) : AgentHelperIpcServer {
    private val listener = AgentIpcListener(path, MAX_CONNECTIONS)
    private val token = authToken.copyOf()
    private val bookmarks = EnvironmentBookmarkTransfers(host.mounts)
    private val handler = host.createRequestHandler(directoryAccess = bookmarks)

    // Never include packet contents or provider errors in diagnostics.
    override suspend fun serve() = listener.serve(childPid = childPid, handle = ::handle)

    private suspend fun handle(client: Int) {
        val session = CliAgentPacketSession(handler, token)
        try {
            var connected = true
            while (connected) {
                val reply = readReply(client, session) ?: break
                connected = sendReply(client, reply)
            }
        } finally {
            session.close()
        }
    }

    private suspend fun readReply(client: Int, session: CliAgentPacketSession): CliAgentPacketReply? {
        val packet = withTimeoutOrNull(IO_TIMEOUT_MS) {
            readPacket(client, CliAgentPacketSession.MAX_PACKET_SIZE)
        } ?: return null
        return processPacket(client, session, packet)
    }

    private suspend fun sendReply(client: Int, reply: CliAgentPacketReply): Boolean = try {
        withTimeoutOrNull(IO_TIMEOUT_MS) {
            writePacket(client, reply.packet, CliAgentPacketSession.MAX_PACKET_SIZE)
        } == true && !reply.close
    } finally {
        reply.packet.fill(0)
    }

    private suspend fun processPacket(
        client: Int,
        session: CliAgentPacketSession,
        packet: ByteArray,
    ): CliAgentPacketReply? = try {
        withTimeoutOrNull<CliAgentPacketReply?>(IO_TIMEOUT_MS) {
            coroutineScope<CliAgentPacketReply?> {
                val response = async { session.process(packet) }
                val disconnect = async { awaitDisconnect(client) }
                try {
                    select<CliAgentPacketReply?> {
                        response.onAwait { it }
                        disconnect.onAwait { response.cancel(); null }
                    }
                } finally {
                    disconnect.cancel()
                }
            }
        }
    } finally {
        packet.fill(0)
    }

    override fun close() {
        listener.close()
        token.fill(0)
        bookmarks.close()
    }
}

/** One private request is outstanding per connection. EOF cancels its approval;
 * additional data while processing is rejected instead of being pipelined. */
@OptIn(ExperimentalForeignApi::class)
private suspend fun awaitDisconnect(fd: Int) {
    val byte = ByteArray(1)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = byte.usePinned { recv(fd, it.addressOf(0), 1u, MSG_PEEK or MSG_DONTWAIT) }
        when {
            count >= 0L -> return
            errno == EINTR -> continue
            errno == EAGAIN || errno == EWOULDBLOCK -> awaitReadable(fd)
            else -> return
        }
    }
}
