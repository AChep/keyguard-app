package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration

internal data class RetryPolicy(
    val maxAttempts: Int,
    val delayBeforeRetry: (failedAttempt: Int) -> Duration,
    val shouldRetry: (Throwable) -> Boolean,
) {
    init {
        require(maxAttempts >= 1) {
            "A retry policy must allow at least one attempt."
        }
    }
}

internal data class RetryEvent(
    val failedAttempt: Int,
    val delay: Duration,
    val error: Throwable,
)

/**
 * Executes a logical operation with a bounded, cancellation-safe retry policy.
 *
 * The caller owns the retry boundary and error classification. This helper only
 * provides common attempt counting, delay, cancellation, and notification mechanics.
 */
internal suspend fun <T> retryWithPolicy(
    policy: RetryPolicy,
    onRetry: suspend (RetryEvent) -> Unit = {},
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            if (attempt >= policy.maxAttempts || !policy.shouldRetry(e)) {
                throw e
            }

            val retryDelay = policy.delayBeforeRetry(attempt)
            require(!retryDelay.isNegative()) {
                "A retry delay must not be negative."
            }
            val event = RetryEvent(
                failedAttempt = attempt,
                delay = retryDelay,
                error = e,
            )
            onRetry(event)
            delay(retryDelay)
            attempt += 1
        }
    }
}
