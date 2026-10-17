package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.AgentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A run of an agent that could not start, or that stopped on its own. */
data class AgentFailure(
    val error: Throwable,
    /** How many runs in a row have failed, this one included. */
    val attempt: Int,
    /** Whether the supervisor restarts the agent after a delay. */
    val willRetry: Boolean,
)

/** When a supervised agent is restarted after it fails. */
data class AgentRestartPolicy(
    val initialDelay: Duration = 2.seconds,
    val maxDelay: Duration = 30.seconds,
    /** A run that lasted this long resets the failure count. */
    val stableRun: Duration = 1.minutes,
    /** After this many failures in a row, the agent stays failed until it is enabled again. */
    val maxConsecutiveFailures: Int = 5,
) {
    /** The delay before the restart that follows the failure [attempt]. */
    fun delayBefore(attempt: Int): Duration {
        var delay = initialDelay
        repeat(attempt - 1) { delay = minOf(delay * 2, maxDelay) }
        return delay
    }
}

/** The agent stopped on its own; the [diagnostic] says how, if known. */
class AgentStoppedException(
    diagnostic: String?,
) : IllegalStateException(diagnostic ?: "The agent stopped unexpectedly")

/**
 * Runs an agent while it is [enabled], restarts it after it fails, and
 * reports its [status]. Every agent runs through here, so they agree on
 * restarts, on backoff and on when to give up.
 *
 * Each run has its own scope. Disabling or restarting the agent waits
 * for its [stop] and for all work in that scope to complete.
 *
 * @param available Whether this build has the agent; without it, the
 * status is [AgentStatus.Unsupported].
 * @param start Starts one agent in the given scope; returns once it is ready.
 * Retries belong to this supervisor so every attempt has its own scope.
 * @param join Suspends until the agent has stopped; returns a diagnostic,
 * such as its exit code.
 * @param stop Releases the agent, `null` if it failed to start. Must also
 * release partially acquired resources and unblock any native I/O before
 * returning; the run's remaining coroutines are then cancelled and awaited.
 */
@Suppress("LongParameterList") // The lifecycle of an agent has these steps.
suspend fun <T : Any> superviseAgent(
    enabled: Flow<Boolean>,
    available: Boolean,
    status: (AgentStatus) -> Unit,
    onFailure: suspend (AgentFailure) -> Unit = {},
    policy: AgentRestartPolicy = AgentRestartPolicy(),
    timeSource: TimeSource = TimeSource.Monotonic,
    start: suspend (CoroutineScope) -> T,
    join: suspend (T) -> String?,
    stop: suspend (T?) -> Unit,
): Unit = coroutineScope {
    enabled
        .distinctUntilChanged()
        .collectLatest { needed ->
            if (!needed) {
                status(AgentStatus.Stopped)
                return@collectLatest
            }
            if (!available) {
                status(AgentStatus.Unsupported)
                return@collectLatest
            }
            var failures = 0
            while (currentCoroutineContext().isActive) {
                val startedAt = timeSource.markNow()
                val error = runAgent(status, start, join, stop)
                failures = if (startedAt.elapsedNow() >= policy.stableRun) 1 else failures + 1
                val willRetry = failures < policy.maxConsecutiveFailures
                status(AgentStatus.Failed)
                onFailure(AgentFailure(error, failures, willRetry))
                if (!willRetry) return@collectLatest
                delay(policy.delayBefore(failures))
            }
        }
}

/** One run of the agent; returns why it ended. */
@Suppress("TooGenericExceptionCaught") // Whatever stops an agent is reported, then retried.
private suspend fun <T : Any> runAgent(
    status: (AgentStatus) -> Unit,
    start: suspend (CoroutineScope) -> T,
    join: suspend (T) -> String?,
    stop: suspend (T?) -> Unit,
): Throwable {
    return try {
        coroutineScope {
            val runJob = coroutineContext.job
            var agent: T? = null
            try {
                status(AgentStatus.Starting)
                agent = start(this)
                status(AgentStatus.Ready)
                AgentStoppedException(join(agent))
            } finally {
                withContext(NonCancellable) {
                    try {
                        // Closing the process and transport unblocks native I/O
                        // before this scope waits for its children to finish.
                        stop(agent)
                    } finally {
                        runJob.cancelChildren()
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
}
