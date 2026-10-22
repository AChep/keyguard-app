package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcPeerVerificationPolicy
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.findAgentBinary
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.cli.CliServiceException
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Runs the agent that serves the CLI, MCP, and local `.env` files. */
class CliAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    private val host: CliAgentHost,
) : AgentManager(logRepository, cryptoGenerator, cliAgentConfig()) {
    /** The bundled `keyguard` CLI, `null` without the agent that serves it. */
    val cliBinaryPath: Path? by lazy {
        defaultBinaryPath?.let { findAgentBinary("keyguard") }
    }

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
        expectedPeerProcess: Deferred<Process>,
    ): IpcServerRunner {
        val handler = host.createRequestHandler()
        val server = AgentIpcServer(
            logRepository = logRepository, scope = scope, tag = "CliAgentIpc",
            // Mount long polls and interactive requests each own a connection.
            maxConcurrentConnections = 32,
            peerVerificationPolicy = AgentIpcPeerVerificationPolicy.ExactProcess(expectedPeerProcess),
            session = { channel ->
                val session = CliAgentPacketSession(handler, authToken)
                try {
                    val auth = channel.readPacket()
                    if (auth != null) {
                        val reply = try { session.process(auth) } finally { auth.fill(0) }
                        try { channel.writePacket(reply.packet) } finally { reply.packet.fill(0) }
                        if (!reply.close) {
                            val packet = channel.readPacket()
                            if (packet != null) coroutineScope {
                                val operation = async { try { session.process(packet) } finally { packet.fill(0) } }
                                // One request per connection. EOF (or unexpected further input)
                                // cancels its native approval before any subsequent side effect.
                                // This observer uses application scope: closing the connection on
                                // return unblocks its IO without waiting for a blocking read here.
                                val disconnect = scope.launch(Dispatchers.IO) {
                                    try { channel.readPacket()?.fill(0) } finally { operation.cancel() }
                                }
                                try {
                                    val response = operation.await()
                                    try { channel.writePacket(response.packet) } finally { response.packet.fill(0) }
                                } finally {
                                    disconnect.cancel()
                                    operation.cancel()
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Parsing/codec exceptions may embed request bytes; do not pass
                    // them into the shared transport's diagnostic logger.
                    throw CliServiceException("connection_closed")
                } finally {
                    session.close()
                }
            },
        )
        return object : IpcServerRunner {
            override suspend fun start(endpoint: AgentIpcEndpoint, onReady: CompletableDeferred<Unit>?) =
                server.start(endpoint, onReady)
            override fun stop() = server.stop()
        }
    }
}

internal fun cliAgentConfig() = AgentManager.Config(
    tag = "CliAgent",
    displayName = "CLI agent",
    binaryBaseName = "keyguard-cli-agent",
    ipcSocketPrefix = "keyguard-cli-ipc",
    agentSocketArg = "--cli-socket",
    agentSocketLogLabel = "CLI socket",
    defaultAgentSocketPath = macosDevAgentSocketPath("cli.sock"),
)
