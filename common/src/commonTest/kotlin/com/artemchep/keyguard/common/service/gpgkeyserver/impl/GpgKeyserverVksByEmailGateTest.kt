package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class GpgKeyserverVksByEmailGateTest {
    private val interval = 65_000L

    private fun TestScope.createGate() = GpgKeyserverVksByEmailGate<String>(
        intervalMillis = interval,
        now = { testScheduler.currentTime },
    )

    @Test
    fun `first lookup runs immediately`() = runTest {
        val gate = createGate()
        var calls = 0

        val result = gate.execute("a") { calls++; "A" }

        assertEquals("A", result)
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `repeated lookup within the window is served from cache without waiting`() = runTest {
        val gate = createGate()
        var calls = 0

        gate.execute("a") { calls++; "A" }
        val startedAt = testScheduler.currentTime
        val result = gate.execute("a") { calls++; "A2" }

        assertEquals("A", result)
        assertEquals(1, calls)
        assertEquals(startedAt, testScheduler.currentTime)
    }

    @Test
    fun `different lookup waits for the throttle interval`() = runTest {
        val gate = createGate()
        var calls = 0

        gate.execute("a") { calls++; "A" }
        val result = gate.execute("b") { calls++; "B" }

        assertEquals("B", result)
        assertEquals(2, calls)
        assertEquals(interval, testScheduler.currentTime)
    }

    @Test
    fun `cached lookup does not reset the throttle for other lookups`() = runTest {
        val gate = createGate()
        var calls = 0

        gate.execute("a") { calls++; "A" }
        advanceTimeBy(interval / 2)
        gate.execute("a") { calls++; "A2" }
        gate.execute("b") { calls++; "B" }

        assertEquals(2, calls)
        // The wait is measured from the real request, not the cache hit.
        assertEquals(interval, testScheduler.currentTime)
    }

    @Test
    fun `failed lookup is not cached`() = runTest {
        val gate = createGate()
        var calls = 0

        assertFailsWith<IllegalStateException> {
            gate.execute("a") { calls++; error("boom") }
        }
        val result = gate.execute("a") { calls++; "A" }

        assertEquals("A", result)
        assertEquals(2, calls)
        // The failed request still counts against the rate limit.
        assertEquals(interval, testScheduler.currentTime)
    }

    @Test
    fun `expired cache entry is fetched again`() = runTest {
        val gate = createGate()
        var calls = 0

        gate.execute("a") { calls++; "A" }
        advanceTimeBy(interval)
        val result = gate.execute("a") { calls++; "A2" }

        assertEquals("A2", result)
        assertEquals(2, calls)
        assertEquals(interval, testScheduler.currentTime)
    }
}
