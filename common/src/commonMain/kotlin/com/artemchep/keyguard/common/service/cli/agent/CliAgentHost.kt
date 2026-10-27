package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.cli.CliRuntimeHost
import com.artemchep.keyguard.common.service.environment.EnvironmentBrokerHandler
import com.artemchep.keyguard.common.service.environment.EnvironmentDirectoryAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentMcpHost
import com.artemchep.keyguard.common.service.environment.EnvironmentMountsHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The features that the CLI agent serves: the commands of the CLI, MCP,
 * and local `.env` files. Platform runtimes start the agent while any of
 * them [needs it][needsAgent].
 */
class CliAgentHost(
    val cli: CliRuntimeHost,
    val mcp: EnvironmentMcpHost,
    val mounts: EnvironmentMountsHost,
    private val commands: CliCommandDispatcher,
    private val environments: (EnvironmentDirectoryAccess) -> EnvironmentBrokerHandler,
) {
    private val mutableStatus = MutableStateFlow<AgentStatus>(AgentStatus.Stopped)

    /** The status of the agent, as the platform runtime reports it. */
    val status: StateFlow<AgentStatus> = mutableStatus.asStateFlow()

    val needsAgent: Flow<Boolean> = combine(cli.state, mcp.state, mounts.state) { cli, mcp, mounts ->
        cli.needsAgent || mcp.enabled || mounts.mounts.any { it.enabled }
    }.distinctUntilChanged()

    /**
     * The handler of the private requests of one agent connection, which
     * gives the agent access to the directories of the local `.env` files
     * with [directoryAccess].
     */
    fun createRequestHandler(
        directoryAccess: EnvironmentDirectoryAccess = EnvironmentDirectoryAccess.ByPath,
    ) = CliAgentRequestHandler(
        cli = commands,
        environments = environments(directoryAccess),
    )

    /** Tells the features that the agent serves about its [status]. */
    fun reportStatus(status: AgentStatus) {
        mutableStatus.value = status
        mounts.onAgentStatus(ready = status == AgentStatus.Ready)
    }
}
