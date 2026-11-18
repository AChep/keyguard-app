package com.artemchep.keyguard.common.service.history.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.history.HistoryRetentionWorker
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.GetGeneratorHistoryRetention
import com.artemchep.keyguard.common.usecase.RemoveAgentHistoryOlderThan
import com.artemchep.keyguard.common.usecase.RemoveCipherUsageHistoryOlderThan
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryOlderThan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HistoryRetentionWorkerImpl(
    private val getAgentHistoryRetention: GetAgentHistoryRetention,
    private val removeAgentHistoryOlderThan: RemoveAgentHistoryOlderThan,
    private val getGeneratorHistoryRetention: GetGeneratorHistoryRetention,
    private val removeGeneratorHistoryOlderThan: RemoveGeneratorHistoryOlderThan,
    private val removeCipherUsageHistoryOlderThan: RemoveCipherUsageHistoryOlderThan,
    private val logRepository: LogRepository,
    private val now: () -> Instant = { Clock.System.now() },
) : HistoryRetentionWorker {
    companion object {
        private val CIPHER_USAGE_RETENTION = 180.days

        // Let unlock and the pending agent request log flush finish first.
        internal val INITIAL_DELAY = 15.seconds
        internal val INTERVAL = 1.hours
    }

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        launch {
            cleanHistory(
                flowOf(CIPHER_USAGE_RETENTION),
                "vault item usage history",
                removeCipherUsageHistoryOlderThan::invoke,
            )
        }
        launch {
            cleanHistory(getAgentHistoryRetention(), "request log", removeAgentHistoryOlderThan)
        }
        launch {
            cleanHistory(getGeneratorHistoryRetention(), "generator history", removeGeneratorHistoryOlderThan::invoke)
        }
    }

    private suspend fun cleanHistory(
        retentionFlow: Flow<Duration>,
        historyName: String,
        removeOlderThan: (Instant) -> IO<Unit>,
    ) {
        retentionFlow.collectLatest { retention ->
            if (retention.isInfinite() || retention <= Duration.ZERO) return@collectLatest

            delay(INITIAL_DELAY)
            while (true) {
                removeOlderThan(now() - retention)
                    .attempt()
                    .bind()
                    .onLeft { e ->
                        logRepository.post(
                            tag = "HistoryRetentionWorker",
                            message = "Failed to delete old $historyName entries: ${e.message}",
                            level = LogLevel.WARNING,
                        )
                    }
                delay(INTERVAL)
            }
        }
    }
}
