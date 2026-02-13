package com.artemchep.keyguard.common.io

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class IOTimeoutTest {
    @Test
    fun `timeout wraps TimeoutCancellationException with preserved cause`() = runTest {
        val exception = assertFailsWith<TimeoutException> {
            ioEffect {
                delay(1_000L)
                1
            }
                .timeout(10L)
                .bind()
        }

        val cause = assertNotNull(exception.cause)
        assertIs<TimeoutCancellationException>(cause)
    }

    @Test
    fun `timeout does not alter success path`() = runTest {
        val value = ioEffect { 123 }
            .timeout(1_000L)
            .bind()

        assertEquals(123, value)
    }
}
