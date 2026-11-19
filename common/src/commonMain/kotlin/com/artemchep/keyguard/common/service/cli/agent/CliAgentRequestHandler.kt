package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.browser.BrowserExtensionStreams
import com.artemchep.keyguard.common.service.browser.UnsupportedBrowserExtension
import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.environment.EnvironmentBrokerHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Routes the private requests of one agent connection to the feature that
 * serves them: commands of the CLI, the environments' MCP management and
 * local `.env` files, or the streams of browser extensions. Never use this
 * router for a public socket.
 *
 * Each request of a client, but not the agent's own housekeeping, ends
 * up in the request log of the [recorder].
 */
class CliAgentRequestHandler(
    private val cli: CliCommandDispatcher,
    private val environments: EnvironmentBrokerHandler,
    /** Serves the browser extensions; by default, their streams are refused. */
    private val browser: BrowserExtensionStreams = UnsupportedBrowserExtension,
    private val recorder: CliAgentUsageRecorder? = null,
) {
    /**
     * Answers [request]. A request that opens a stream gets it with the
     * answer, and the caller owns it, see [CliAgentAnswer.upgrade]. Such a
     * request is never recorded: the stream logs what it carries.
     */
    suspend fun handle(request: CliAgentMessages.Request.Kind): CliAgentAnswer {
        val recorder = recorder
        val usage = request.toUsageRecord()
        if (recorder == null || usage == null) {
            return route(request, usage = null)
        }
        val answer = try {
            route(request, usage)
        } catch (e: CancellationException) {
            // The client left; the request log still shows that it asked.
            withContext(NonCancellable) {
                recorder.record(usage, response = null)
            }
            throw e
        }
        recorder.record(usage, answer.response)
        return answer
    }

    private suspend fun route(
        request: CliAgentMessages.Request.Kind,
        usage: CliAgentUsageRecord?,
    ): CliAgentAnswer = when (request) {
        is CliAgentMessages.Request.Kind.Cli -> CliAgentAnswer(cli.handle(request.value, usage))
        is CliAgentMessages.Request.Kind.Manage,
        is CliAgentMessages.Request.Kind.Mounts,
        is CliAgentMessages.Request.Kind.WatchMounts,
        is CliAgentMessages.Request.Kind.MountStatus,
        is CliAgentMessages.Request.Kind.ResolveMount,
        -> CliAgentAnswer(environments.handle(request, usage))
        is CliAgentMessages.Request.Kind.BrowserOpen -> browser.open(request.value)
        // A connection authenticates once, with its first request.
        is CliAgentMessages.Request.Kind.Authenticate -> CliAgentAnswer(cliAgentError("invalid_request"))
    }
}
