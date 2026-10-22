package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.environment.EnvironmentBrokerHandler

/**
 * Routes the private requests of one agent connection to the feature that
 * serves them: commands of the CLI, or the environments' MCP management
 * and local `.env` files. Never use this router for a public socket.
 */
class CliAgentRequestHandler(
    private val cli: CliCommandDispatcher,
    private val environments: EnvironmentBrokerHandler,
) {
    suspend fun handle(request: CliAgentMessages.Request.Kind): CliAgentMessages.Response.Kind = when (request) {
        is CliAgentMessages.Request.Kind.Cli -> cli.handle(request.value)
        is CliAgentMessages.Request.Kind.Manage,
        is CliAgentMessages.Request.Kind.Mounts,
        is CliAgentMessages.Request.Kind.WatchMounts,
        is CliAgentMessages.Request.Kind.MountStatus,
        is CliAgentMessages.Request.Kind.ResolveMount,
        -> environments.handle(request)
        // A connection authenticates once, with its first request.
        is CliAgentMessages.Request.Kind.Authenticate -> cliAgentError("invalid_request")
    }
}
