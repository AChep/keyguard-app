package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentFailure
import com.artemchep.keyguard.common.service.agent.superviseAgent
import kotlinx.coroutines.CoroutineScope

/**
 * Runs the agent while a feature [needs][CliAgentHost.needsAgent] it,
 * through the supervisor shared by every agent, and
 * [reports][CliAgentHost.reportStatus] its status.
 *
 * @param available Whether this build has the agent.
 * @param start Starts the agent in the given scope; returns once it is ready.
 * @param join Suspends until the agent has stopped; returns a diagnostic, if any.
 * @param stop Releases the agent, `null` if it failed to start.
 */
suspend fun <T : Any> CliAgentHost.supervise(
    available: Boolean = true,
    onFailure: suspend (AgentFailure) -> Unit = {},
    start: suspend (CoroutineScope) -> T,
    join: suspend (T) -> String?,
    stop: suspend (T?) -> Unit,
): Unit = superviseAgent(
    enabled = needsAgent,
    available = available,
    status = this::reportStatus,
    onFailure = onFailure,
    start = start,
    join = join,
    stop = stop,
)

