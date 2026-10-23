package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.findAgentBinary
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope

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
    ): IpcServerRunner {
        val server = CliAgentIpcServer(
            logRepository = logRepository,
            authToken = authToken,
            handler = host.createRequestHandler(),
        )
        return object : IpcServerRunner {
            override fun bind(location: AgentIpcLocation) = server.bind(location)

            override suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
                server.serve(expectedPeer)
            }

            override fun close() {
                server.close()
            }
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
