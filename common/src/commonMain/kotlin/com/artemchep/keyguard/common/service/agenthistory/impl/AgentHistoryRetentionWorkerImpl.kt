package com.artemchep.keyguard.common.service.agenthistory.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.service.agenthistory.AgentHistoryRetentionWorker
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.RemoveAgentHistoryOlderThan
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentHistoryRetentionWorkerImpl(
    private val getAgentHistoryRetention: GetAgentHistoryRetention,
    private val removeAgentHistoryOlderThan: RemoveAgentHistoryOlderThan,
    private val logRepository: LogRepository,
    private val now: () -> Instant = { Clock.System.now() },
) : AgentHistoryRetentionWorker {
    companion object {
        private const val TAG = "AgentHistoryRetentionWorker"

        /**
         * Gives the pending usage history flusher time to move the
         * requests made while the vault was locked into the logs, and
         * keeps the cleanup from competing with the unlock.
         */
        internal val INITIAL_DELAY = 15.seconds

        internal val INTERVAL = 1.hours
    }

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        getAgentHistoryRetention()
            .collectLatest { retention ->
                if (retention.isInfinite() || retention <= Duration.ZERO) {
                    return@collectLatest
                }

                delay(INITIAL_DELAY)
                while (coroutineContext.isActive) {
                    removeOlderThan(retention)
                    delay(INTERVAL)
                }
            }
    }

    private suspend fun removeOlderThan(retention: Duration) {
        val cutoff = now() - retention
        removeAgentHistoryOlderThan(cutoff)
            .attempt()
            .bind()
            .onLeft { e ->
                logRepository.post(
                    tag = TAG,
                    message = "Failed to delete old request log entries: ${e.message}",
                    level = LogLevel.WARNING,
                )
            }
    }
}
