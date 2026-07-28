package com.artemchep.keyguard.common.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {
    @Test
    fun `retry waits for the configured delay before the next attempt`() = runTest {
        val attempts = mutableListOf<Long>()

        val result = async {
            retryWithPolicy(testPolicy()) { attempt ->
                attempts += currentTime
                if (attempt == 1) throw RetryableTestException(attempt)
                "success"
            }
        }

        runCurrent()
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(listOf(0L), attempts)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals("success", result.await())
        assertEquals(listOf(0L, 1_000L), attempts)
    }

    @Test
    fun `three retries allow four total attempts`() = runTest {
        val attempts = mutableListOf<Long>()

        val error = assertFailsWith<RetryableTestException> {
            retryWithPolicy(testPolicy()) { attempt ->
                attempts += currentTime
                throw RetryableTestException(attempt)
            }
        }

        assertEquals(4, error.attempt)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), attempts)
    }

    @Test
    fun `non matching error is not retried`() = runTest {
        val expected = IllegalArgumentException("not retryable")
        var attempts = 0

        val error = assertFailsWith<IllegalArgumentException> {
            retryWithPolicy(testPolicy()) {
                attempts += 1
                throw expected
            }
        }

        assertSame(expected, error)
        assertEquals(1, attempts)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `cancellation during retry delay prevents another attempt`() = runTest {
        var attempts = 0
        val result = async {
            retryWithPolicy(testPolicy()) { attempt ->
                attempts += 1
                throw RetryableTestException(attempt)
            }
        }

        runCurrent()
        assertEquals(1, attempts)
        result.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            result.await()
        }
        advanceTimeBy(4_000L)
        runCurrent()

        assertEquals(1, attempts)
    }

    private fun testPolicy() = RetryPolicy(
        maxAttempts = 4,
        delayBeforeRetry = { 1.seconds },
        shouldRetry = { it is RetryableTestException },
    )

    private class RetryableTestException(
        val attempt: Int,
    ) : IllegalStateException("retryable failure on attempt $attempt")
}
