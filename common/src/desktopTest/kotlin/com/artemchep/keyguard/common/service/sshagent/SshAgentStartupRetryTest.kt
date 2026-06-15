package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentStartupRetryTest {
    private class RecordingLogRepository : LogRepository {
        val entries = mutableListOf<Pair<LogLevel, String>>()

        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            entries += level to "[$tag] $message"
        }

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            entries += level to "[$tag] $message"
        }
    }

    @Test
    fun `returns first successful startup result without retry`() = runTest {
        val logRepository = RecordingLogRepository()
        val attempts = mutableListOf<Int>()
        var stopCalls = 0

        val result = retrySshAgentStartup(
            logRepository = logRepository,
            start = { attempt, _ ->
                attempts += attempt
                "process"
            },
            stop = {
                stopCalls++
            },
        )

        assertEquals("process", result)
        assertContentEquals(listOf(1), attempts)
        assertEquals(0, stopCalls)
        assertEquals(0, logRepository.entries.size)
    }

    @Test
    fun `retries after transient failure and succeeds on second attempt`() = runTest {
        val logRepository = RecordingLogRepository()
        val attempts = mutableListOf<Int>()
        var stopCalls = 0

        val result = retrySshAgentStartup(
            logRepository = logRepository,
            start = { attempt, _ ->
                attempts += attempt
                if (attempt == 1) {
                    error("transient")
                }
                "process"
            },
            stop = {
                stopCalls++
            },
        )

        assertEquals("process", result)
        assertContentEquals(listOf(1, 2), attempts)
        assertEquals(1, stopCalls)
        assertEquals(1, logRepository.entries.size)
        assertEquals(LogLevel.WARNING, logRepository.entries.single().first)
    }

    @Test
    fun `rethrows final failure after all attempts are exhausted`() = runTest {
        val logRepository = RecordingLogRepository()
        val attempts = mutableListOf<Int>()
        var stopCalls = 0

        val error = assertFailsWith<IllegalStateException> {
            retrySshAgentStartup(
                logRepository = logRepository,
                start = { attempt, _ ->
                    attempts += attempt
                    throw IllegalStateException("boom $attempt")
                },
                stop = {
                    stopCalls++
                },
            )
        }

        assertEquals("boom 3", error.message)
        assertContentEquals(listOf(1, 2, 3), attempts)
        assertEquals(3, stopCalls)
        assertContentEquals(
            listOf(LogLevel.WARNING, LogLevel.WARNING, LogLevel.ERROR),
            logRepository.entries.map { it.first },
        )
    }

    @Test
    fun `cancellation during backoff stops cleanup and does not retry`() = runTest {
        val logRepository = RecordingLogRepository()
        val attempts = mutableListOf<Int>()
        var stopCalls = 0

        val deferred = async {
            retrySshAgentStartup(
                logRepository = logRepository,
                start = { attempt, _ ->
                    attempts += attempt
                    error("transient")
                },
                stop = {
                    stopCalls++
                },
            )
        }

        runCurrent()
        assertContentEquals(listOf(1), attempts)
        assertEquals(1, stopCalls)

        deferred.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            deferred.await()
        }

        assertContentEquals(listOf(1), attempts)
        assertEquals(2, stopCalls)
        assertEquals(1, logRepository.entries.size)
    }

    @Test
    fun `cancellation during startup propagates and cleans up once`() = runTest {
        val logRepository = RecordingLogRepository()
        var stopCalls = 0
        val startupGate = CompletableDeferred<Unit>()

        val deferred = async {
            retrySshAgentStartup(
                logRepository = logRepository,
                start = { _, _ ->
                    startupGate.await()
                    "process"
                },
                stop = {
                    stopCalls++
                },
            )
        }

        runCurrent()
        deferred.cancel()

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            deferred.await()
        }
        assertEquals(1, stopCalls)
        assertEquals(0, logRepository.entries.size)
    }
}
