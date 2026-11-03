package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.environment.EnvironmentBrokerHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Routes the private requests of one agent connection to the feature that
 * serves them: commands of the CLI, or the environments' MCP management
 * and local `.env` files. Never use this router for a public socket.
 *
 * Each request of a client, but not the agent's own housekeeping, ends
 * up in the request log of the [recorder].
 */
class CliAgentRequestHandler(
    private val cli: CliCommandDispatcher,
    private val environments: EnvironmentBrokerHandler,
    private val recorder: CliAgentUsageRecorder? = null,
) {
    suspend fun handle(request: CliAgentMessages.Request.Kind): CliAgentMessages.Response.Kind {
        val recorder = recorder
        val usage = request.toUsageRecord()
        if (recorder == null || usage == null) {
            return route(request, usage = null)
        }
        val response = try {
            route(request, usage)
        } catch (e: CancellationException) {
            // The client left; the request log still shows that it asked.
            withContext(NonCancellable) {
                recorder.record(usage, response = null)
            }
            throw e
        }
        recorder.record(usage, response)
        return response
    }

    private suspend fun route(
        request: CliAgentMessages.Request.Kind,
        usage: CliAgentUsageRecord?,
    ): CliAgentMessages.Response.Kind = when (request) {
        is CliAgentMessages.Request.Kind.Cli -> cli.handle(request.value, usage)
        is CliAgentMessages.Request.Kind.Manage,
        is CliAgentMessages.Request.Kind.Mounts,
        is CliAgentMessages.Request.Kind.WatchMounts,
        is CliAgentMessages.Request.Kind.MountStatus,
        is CliAgentMessages.Request.Kind.ResolveMount,
        -> environments.handle(request, usage)
        // A connection authenticates once, with its first request.
        is CliAgentMessages.Request.Kind.Authenticate -> cliAgentError("invalid_request")
    }
}
