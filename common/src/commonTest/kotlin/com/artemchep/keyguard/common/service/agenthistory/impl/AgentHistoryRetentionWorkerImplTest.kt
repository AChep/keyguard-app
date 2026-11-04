package com.artemchep.keyguard.common.service.agenthistory.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.RemoveAgentHistoryOlderThan
import com.artemchep.keyguard.test.RecordingLogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgentHistoryRetentionWorkerImplTest {
    private val initialDelay = AgentHistoryRetentionWorkerImpl.INITIAL_DELAY
    private val interval = AgentHistoryRetentionWorkerImpl.INTERVAL

    @Test
    fun `infinite retention never removes entries`() = runTest {
        val remove = RecordingRemoveAgentHistoryOlderThan()
        val worker = createWorker(
            retention = MutableStateFlow(Duration.INFINITE),
            remove = remove,
        )

        val job = worker.launch(this)
        try {
            advanceTimeBy(initialDelay + interval * 3)
            runCurrent()

            assertEquals(emptyList(), remove.cutoffs)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `finite retention removes entries after the initial delay and then every interval`() = runTest {
        val remove = RecordingRemoveAgentHistoryOlderThan()
        val worker = createWorker(
            retention = MutableStateFlow(7.days),
            remove = remove,
        )

        val job = worker.launch(this)
        try {
            advanceTimeBy(initialDelay - 1.milliseconds)
            runCurrent()
            assertEquals(emptyList(), remove.cutoffs)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(listOf(now() - 7.days), remove.cutoffs)

            advanceTimeBy(interval)
            runCurrent()
            assertEquals(2, remove.cutoffs.size)
            assertEquals(now() - 7.days, remove.cutoffs.last())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `changing the retention restarts the cleanup with the new cutoff`() = runTest {
        val retention = MutableStateFlow(7.days)
        val remove = RecordingRemoveAgentHistoryOlderThan()
        val worker = createWorker(
            retention = retention,
            remove = remove,
        )

        val job = worker.launch(this)
        try {
            advanceTimeBy(initialDelay)
            runCurrent()
            assertEquals(1, remove.cutoffs.size)

            retention.value = 1.days
            runCurrent()
            advanceTimeBy(initialDelay)
            runCurrent()
            assertEquals(2, remove.cutoffs.size)
            assertEquals(now() - 1.days, remove.cutoffs.last())

            retention.value = Duration.INFINITE
            runCurrent()
            advanceTimeBy(initialDelay + interval * 3)
            runCurrent()
            assertEquals(2, remove.cutoffs.size)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `a failed removal is logged and retried on the next interval`() = runTest {
        val remove = RecordingRemoveAgentHistoryOlderThan(
            failure = IllegalStateException("Database is closed"),
        )
        val logRepository = RecordingLogRepository()
        val worker = createWorker(
            retention = MutableStateFlow(7.days),
            remove = remove,
            logRepository = logRepository,
        )

        val job = worker.launch(this)
        try {
            advanceTimeBy(initialDelay)
            runCurrent()
            assertEquals(1, remove.cutoffs.size)
            assertEquals(LogLevel.WARNING, logRepository.entries.single().level)

            advanceTimeBy(interval)
            runCurrent()
            assertEquals(2, remove.cutoffs.size)
        } finally {
            job.cancel()
        }
    }

    private fun TestScope.now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)

    private fun TestScope.createWorker(
        retention: Flow<Duration>,
        remove: RemoveAgentHistoryOlderThan,
        logRepository: LogRepository = LogRepositoryBridge(emptyList()),
    ) = AgentHistoryRetentionWorkerImpl(
        getAgentHistoryRetention = object : GetAgentHistoryRetention {
            override fun invoke(): Flow<Duration> = retention
        },
        removeAgentHistoryOlderThan = remove,
        logRepository = logRepository,
        now = { now() },
    )

    private class RecordingRemoveAgentHistoryOlderThan(
        private val failure: Throwable? = null,
    ) : RemoveAgentHistoryOlderThan {
        val cutoffs = mutableListOf<Instant>()

        override fun invoke(cutoff: Instant): IO<Unit> = ioEffect {
            cutoffs += cutoff
            if (failure != null) {
                throw failure
            }
        }
    }
}
