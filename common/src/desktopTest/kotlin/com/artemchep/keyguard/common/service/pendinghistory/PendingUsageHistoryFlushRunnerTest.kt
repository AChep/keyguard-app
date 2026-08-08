package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.RetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PendingUsageHistoryFlushRunnerTest {
    @Test
    fun `transient failure retries after the configured delay`() = runTest {
        var attempts = 0
        val logs = RecordingLogRepository()
        val runner = runner(
            logs = logs,
            delay = 1.seconds,
        ) {
            attempts += 1
            if (attempts == 1) {
                ioEffect { error("transient") }
            } else {
                io(PendingUsageHistoryFlushResult(deferredRows = 0))
            }
        }

        val task = async {
            runner.run()
                .bind()
        }
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(1L)
        task.await()

        assertEquals(2, attempts)
        assertEquals(1, logs.entries.count { it.level == LogLevel.WARNING })
        assertTrue(logs.entries.none { it.level == LogLevel.ERROR })
    }

    @Test
    fun `deferred rows make the runner perform another pass`() = runTest {
        var attempts = 0
        val runner = runner {
            attempts += 1
            io(
                PendingUsageHistoryFlushResult(
                    deferredRows = if (attempts == 1) 1 else 0,
                ),
            )
        }

        runner.run()
            .bind()

        assertEquals(2, attempts)
    }

    @Test
    fun `exhausted failures are logged and contained`() = runTest {
        var attempts = 0
        val logs = RecordingLogRepository()
        val runner = runner(
            logs = logs,
            maxAttempts = 3,
        ) {
            ioEffect {
                attempts += 1
                error("persistent")
            }
        }

        runner.run()
            .bind()

        assertEquals(3, attempts)
        assertEquals(2, logs.entries.count { it.level == LogLevel.WARNING })
        assertEquals(1, logs.entries.count { it.level == LogLevel.ERROR })
    }

    @Test
    fun `cancellation is not logged or retried`() = runTest {
        var attempts = 0
        val logs = RecordingLogRepository()
        val runner = runner(logs = logs) {
            ioEffect {
                attempts += 1
                throw CancellationException("cancel")
            }
        }

        assertFailsWith<CancellationException> {
            runner.run()
                .bind()
        }

        assertEquals(1, attempts)
        assertTrue(logs.entries.isEmpty())
    }

    @Test
    fun `cancellation during backoff stops future attempts`() = runTest {
        var attempts = 0
        val runner = runner(delay = 1.seconds) {
            ioEffect {
                attempts += 1
                error("transient")
            }
        }
        val task = async {
            runner.run()
                .bind()
        }
        runCurrent()
        assertEquals(1, attempts)

        task.cancelAndJoin()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, attempts)
    }

    private fun runner(
        logs: RecordingLogRepository = RecordingLogRepository(),
        maxAttempts: Int = 2,
        delay: Duration = Duration.ZERO,
        flush: () -> com.artemchep.keyguard.common.io.IO<PendingUsageHistoryFlushResult>,
    ) = PendingUsageHistoryFlushRunnerImpl(
        flush = flush,
        logRepository = logs,
        retryPolicy = RetryPolicy(
            maxAttempts = maxAttempts,
            delayBeforeRetry = { delay },
            shouldRetry = { true },
        ),
    )
}

private class RecordingLogRepository : LogRepository {
    data class Entry(
        val message: String,
        val level: LogLevel,
    )

    val entries = mutableListOf<Entry>()

    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        entries += Entry(
            message = message,
            level = level,
        )
    }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = post(
        tag = tag,
        message = message,
        level = level,
    )
}
