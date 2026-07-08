package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.retryAgentStartup
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val STARTUP_RETRY_TAG = "SshAgentStartupRetry"

val defaultSshAgentStartupBackoffDurations = listOf(
    250.milliseconds,
    750.milliseconds,
)

suspend fun <T> retrySshAgentStartup(
    logRepository: LogRepository,
    start: suspend (attempt: Int, maxAttempts: Int) -> T,
    stop: suspend () -> Unit,
    backoffDurations: List<Duration> = defaultSshAgentStartupBackoffDurations,
): T = retryAgentStartup(
    logRepository = logRepository,
    tag = STARTUP_RETRY_TAG,
    agentDisplayName = "SSH",
    start = start,
    stop = stop,
    backoffDurations = backoffDurations,
)
