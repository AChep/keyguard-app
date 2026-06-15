package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
                logSshAgentStartupFailure(
                    logRepository = logRepository,
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

    error("SSH agent startup retry exhausted without returning or throwing")
}

private fun logSshAgentStartupFailure(
    logRepository: LogRepository,
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
    val msg = "SSH agent startup attempt $attempt/$maxAttempts failed, $action: ${e.message}"
    logRepository.post(STARTUP_RETRY_TAG, msg, level)
}
