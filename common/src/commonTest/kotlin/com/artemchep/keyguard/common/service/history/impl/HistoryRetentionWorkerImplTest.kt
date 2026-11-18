package com.artemchep.keyguard.common.service.history.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetention
import com.artemchep.keyguard.common.usecase.GetGeneratorHistoryRetention
import com.artemchep.keyguard.common.usecase.RemoveAgentHistoryOlderThan
import com.artemchep.keyguard.common.usecase.RemoveCipherUsageHistoryOlderThan
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryOlderThan
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
class HistoryRetentionWorkerImplTest {
    private val initialDelay = HistoryRetentionWorkerImpl.INITIAL_DELAY
    private val interval = HistoryRetentionWorkerImpl.INTERVAL

    @Test
    fun `vault item usage expires after 180 days even when other history is kept forever`() = runTest {
        val remove = RecordingRemoveAgentHistoryOlderThan()
        val usageCutoffs = mutableListOf<Instant>()
        val worker = createWorker(
            retention = MutableStateFlow(Duration.INFINITE),
            remove = remove,
            removeCipherUsage = RemoveCipherUsageHistoryOlderThan { cutoff -> ioEffect { usageCutoffs += cutoff } },
        )

        worker.launch(backgroundScope)
        advanceTimeBy(initialDelay)
        assertEquals(emptyList(), usageCutoffs)
        runCurrent()
        val firstCutoff = now() - 180.days
        assertEquals(listOf(firstCutoff), usageCutoffs)

        advanceTimeBy(interval)
        runCurrent()
        assertEquals(listOf(firstCutoff, firstCutoff + interval), usageCutoffs)
        assertEquals(emptyList(), remove.cutoffs)
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

    @Test
    fun `generator retention changes do not restart agent cleanup`() = runTest {
        val agent = RecordingRemoveAgentHistoryOlderThan()
        val generator = RecordingRemoveAgentHistoryOlderThan()
        val retention = MutableStateFlow(Duration.INFINITE)
        val worker = createWorker(
            retention = MutableStateFlow(7.days),
            remove = agent,
            generatorRetention = retention,
            removeGenerator = RemoveGeneratorHistoryOlderThan(generator::invoke),
        )
        worker.launch(backgroundScope)
        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(listOf(now() - 7.days), agent.cutoffs)
        assertEquals(emptyList(), generator.cutoffs)

        retention.value = 14.days
        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(listOf(now() - 14.days), generator.cutoffs)
        retention.value = 1.days
        advanceTimeBy(initialDelay)
        runCurrent()
        assertEquals(now() - 1.days, generator.cutoffs.last())

        retention.value = Duration.INFINITE
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(2, generator.cutoffs.size)
        assertEquals(2, agent.cutoffs.size)
        assertEquals(agent.cutoffs.first() + interval, agent.cutoffs.last())
    }

    private fun TestScope.now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)

    private fun TestScope.createWorker(
        retention: Flow<Duration>,
        remove: RemoveAgentHistoryOlderThan,
        generatorRetention: Flow<Duration> = MutableStateFlow(Duration.INFINITE),
        removeGenerator: RemoveGeneratorHistoryOlderThan = RemoveGeneratorHistoryOlderThan {
            error("Unexpected generator history cleanup")
        },
        removeCipherUsage: RemoveCipherUsageHistoryOlderThan = RemoveCipherUsageHistoryOlderThan { ioUnit() },
        logRepository: LogRepository = LogRepositoryBridge(emptyList()),
    ) = HistoryRetentionWorkerImpl(
        getAgentHistoryRetention = object : GetAgentHistoryRetention {
            override fun invoke(): Flow<Duration> = retention
        },
        removeAgentHistoryOlderThan = remove,
        getGeneratorHistoryRetention = object : GetGeneratorHistoryRetention {
            override fun invoke(): Flow<Duration> = generatorRetention
        },
        removeGeneratorHistoryOlderThan = removeGenerator,
        removeCipherUsageHistoryOlderThan = removeCipherUsage,
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
