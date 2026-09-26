package com.artemchep.keyguard.common.io

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IOMonadTest {
    @Test
    fun `parallel rejects zero parallelism with IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            listOf(io(1))
                .parallel(parallelism = 0)
                .bindBlocking()
        }
    }

    @Test
    fun `parallel returns all values for valid parallelism`() {
        val values = listOf(
            io(1),
            io(2),
            io(3),
            io(4),
        ).parallel(parallelism = 2)
            .bindBlocking()

        assertEquals(listOf(1, 2, 3, 4), values)
    }

    @Test
    fun `parallel never runs more than parallelism at once for uneven sizes`() {
        assertPeakConcurrencyWithinLimit(size = 5, parallelism = 2)
        assertPeakConcurrencyWithinLimit(size = 7, parallelism = 4)
        assertPeakConcurrencyWithinLimit(size = 15, parallelism = 8)
        assertPeakConcurrencyWithinLimit(size = 3, parallelism = 8)
        assertPeakConcurrencyWithinLimit(size = 0, parallelism = 3)
    }

    private fun assertPeakConcurrencyWithinLimit(
        size: Int,
        parallelism: Int,
    ) = runTest {
        var inFlight = 0
        var peak = 0
        val values = List(size) { index ->
            val io: IO<Int> = {
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(10L)
                inFlight--
                index
            }
            io
        }.parallel(parallelism = parallelism)
            .bind()

        assertEquals(List(size) { it }, values, "size=$size parallelism=$parallelism")
        assertTrue(
            peak <= parallelism,
            "size=$size parallelism=$parallelism: peak concurrency was $peak",
        )
    }
}
