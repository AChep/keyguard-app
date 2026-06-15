package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOTimeoutTest {
    @Test
    fun `timeout raises IOTimeoutException when operation exceeds limit`() = runTest {
        val exception = assertFailsWith<IOTimeoutException> {
            ioEffect {
                delay(1_000L)
                1
            }
                .timeout(10L)
                .bind()
        }

        assertEquals("Timed out waiting for 10 ms", exception.message)
    }

    @Test
    fun `timeout does not alter success path`() = runTest {
        val value = ioEffect { 123 }
            .timeout(1_000L)
            .bind()

        assertEquals(123, value)
    }

    @Test
    fun `timeout preserves null result on success path`() = runTest {
        val value = ioEffect<String?> { null }
            .timeout(1_000L)
            .bind()

        assertEquals(null, value)
    }

    @Test
    fun `timeout does not convert coroutine cancellation into IOTimeoutException`() = runTest {
        val deferred = async {
            ioEffect {
                awaitCancellation()
            }
                .timeout(1_000L)
                .bind()
        }

        deferred.cancel()

        assertFailsWith<CancellationException> {
            deferred.await()
        }
    }

    @Test
    fun `timeout does not convert outer coroutine timeout into IOTimeoutException`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10L) {
                ioEffect {
                    delay(1_000L)
                    1
                }
                    .timeout(1_000L)
                    .bind()
            }
        }
    }
}
