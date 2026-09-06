package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.RetryPolicy
import com.artemchep.keyguard.common.util.retryWithPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration.Companion.seconds

interface PendingUsageHistoryFlushRunner {
    fun run(): IO<Unit>
}

internal class PendingUsageHistoryFlushRunnerImpl(
    private val flush: () -> IO<PendingUsageHistoryFlushResult>,
    private val logRepository: LogRepository,
    private val retryPolicy: RetryPolicy = DEFAULT_RETRY_POLICY,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PendingUsageHistoryFlushRunner {
    companion object {
        private const val TAG = "PendingUsageHistoryFlusher"

        private val DEFAULT_RETRY_POLICY = RetryPolicy(
            maxAttempts = 4,
            delayBeforeRetry = { failedAttempt ->
                when (failedAttempt) {
                    1 -> 1.seconds
                    2 -> 2.seconds
                    else -> 4.seconds
                }
            },
            shouldRetry = { true },
        )
    }

    constructor(directDI: DirectDI) : this(
        flush = directDI.instance<PendingUsageHistoryFlusher>()::flush,
        logRepository = directDI.instance(),
    )

    @Suppress("TooGenericExceptionCaught")
    override fun run(): IO<Unit> = ioEffect(defaultDispatcher) {
        try {
            retryWithPolicy(
                policy = retryPolicy,
                onRetry = { event ->
                    logRepository.post(
                        tag = TAG,
                        message = "Pending usage history flush attempt " +
                                "${event.failedAttempt} failed; retrying in ${event.delay}.",
                        level = LogLevel.WARNING,
                    )
                },
            ) {
                val result = flush()
                    .bind()
                if (!result.isComplete) {
                    throw PendingUsageHistoryFlushIncompleteException(
                        deferredRows = result.deferredRows,
                    )
                }
            }
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(
                tag = TAG,
                message = "Pending usage history flush failed after " +
                        "${retryPolicy.maxAttempts} attempts: ${e.message}",
                level = LogLevel.ERROR,
            )
        }
    }
}

private class PendingUsageHistoryFlushIncompleteException(
    deferredRows: Int,
) : IllegalStateException(
    "$deferredRows pending usage history row(s) remain queued.",
)
