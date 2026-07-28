package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.retryAgentStartup
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val STARTUP_RETRY_TAG = "GpgAgentStartupRetry"

val defaultGpgAgentStartupBackoffDurations = listOf(
    250.milliseconds,
    750.milliseconds,
)

suspend fun <T> retryGpgAgentStartup(
    logRepository: LogRepository,
    start: suspend (attempt: Int, maxAttempts: Int) -> T,
    stop: suspend () -> Unit,
    backoffDurations: List<Duration> = defaultGpgAgentStartupBackoffDurations,
): T = retryAgentStartup(
    logRepository = logRepository,
    tag = STARTUP_RETRY_TAG,
    agentDisplayName = "GPG",
    start = start,
    stop = stop,
    backoffDurations = backoffDurations,
)
