package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectBackoffTest {
    @Test
    fun `uses capped exponential schedule when jitter is neutral`() {
        val backoff = ReconnectBackoff(
            provideRandomDouble = { 0.5 },
        )

        val delays = buildList {
            repeat(8) {
                add(backoff.nextDelayMs())
            }
        }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            delays,
        )
        assertEquals(8, backoff.attempt)
    }

    @Test
    fun `applies minus twenty percent jitter after cap clamp`() {
        val backoff = ReconnectBackoff(
            provideRandomDouble = { 0.0 },
        )

        repeat(6) {
            backoff.nextDelayMs()
        }
        val delay = backoff.nextDelayMs()

        assertEquals(48_000L, delay)
    }

    @Test
    fun `applies plus twenty percent jitter after cap clamp`() {
        val backoff = ReconnectBackoff(
            provideRandomDouble = { 1.0 },
        )

        repeat(6) {
            backoff.nextDelayMs()
        }
        val delay = backoff.nextDelayMs()

        assertEquals(72_000L, delay)
    }

    @Test
    fun `reset starts attempts from the beginning`() {
        val backoff = ReconnectBackoff(
            provideRandomDouble = { 0.5 },
        )

        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(2, backoff.attempt)

        backoff.reset()

        assertEquals(0, backoff.attempt)
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(1, backoff.attempt)
    }

    @Test
    fun `uses injected random source deterministically`() {
        val values = mutableListOf(0.0, 1.0, 0.25)
        val backoff = ReconnectBackoff(
            provideRandomDouble = { values.removeFirst() },
        )

        assertEquals(800L, backoff.nextDelayMs())
        assertEquals(2_400L, backoff.nextDelayMs())
        assertEquals(3_600L, backoff.nextDelayMs())
    }
}