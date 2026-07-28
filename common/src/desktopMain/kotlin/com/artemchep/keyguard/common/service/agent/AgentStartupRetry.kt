package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration

internal suspend fun <T> retryAgentStartup(
    logRepository: LogRepository,
    tag: String,
    agentDisplayName: String,
    start: suspend (attempt: Int, maxAttempts: Int) -> T,
    stop: suspend () -> Unit,
    backoffDurations: List<Duration>,
): T {
    val maxAttempts = backoffDurations.size + 1
    try {
        for (attempt in 1..maxAttempts) {
            try {
                return start(attempt, maxAttempts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryDelay = backoffDurations.getOrNull(attempt - 1)
                logAgentStartupFailure(
                    logRepository = logRepository,
                    tag = tag,
                    agentDisplayName = agentDisplayName,
                    retryDelay = retryDelay,
                    attempt = attempt,
                    maxAttempts = maxAttempts,
                    e = e,
                )

                withContext(NonCancellable) {
                    stop()
                }
                if (retryDelay == null) {
                    throw e
                }
                delay(retryDelay)
            }
        }
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            stop()
        }
        throw e
    }

    error("$agentDisplayName agent startup retry exhausted without returning or throwing")
}

private fun logAgentStartupFailure(
    logRepository: LogRepository,
    tag: String,
    agentDisplayName: String,
    retryDelay: Duration?,
    attempt: Int,
    maxAttempts: Int,
    e: Exception,
) {
    val level = if (retryDelay != null) {
        LogLevel.WARNING
    } else {
        LogLevel.ERROR
    }
    val action = if (retryDelay != null) {
        "retrying in ${retryDelay.inWholeMilliseconds}ms"
    } else {
        "no retries left"
    }
    val msg = "$agentDisplayName agent startup attempt $attempt/$maxAttempts failed, $action: ${e.message}"
    logRepository.post(tag, msg, level)
}
