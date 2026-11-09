package com.artemchep.keyguard.provider.keyguard.transport

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class TokenBucketTest {
    /** The relay's own limiter: a one-second burst that refills continuously. */
    private class RelayBudget(private val perSecond: Double) {
        private var tokens = perSecond
        private var updatedAt = 0L

        fun spend(cost: Double, now: Long): Boolean {
            tokens = min(perSecond, tokens + (now - updatedAt) / 1_000.0 * perSecond)
            updatedAt = now
            if (tokens < cost) return false
            tokens -= cost
            return true
        }
    }

    @Test
    fun `small messages stay within the relay's message budget`() = runTest {
        val bucket = TokenBucket(RelayPace.of(RelayLimits())) { currentTime }
        val relay = RelayBudget(RELAY_MESSAGES_PER_SECOND)
        repeat(300) {
            bucket.acquire(16)
            assertTrue(relay.spend(1.0, currentTime), "message $it at $currentTime ms was over budget")
        }
        // ...while sending at most of the rate.
        assertTrue(currentTime < 300L * 1_000L / 30L)
    }

    @Test
    fun `large messages stay within the relay's byte budget`() = runTest {
        val bucket = TokenBucket(RelayPace.of(RelayLimits())) { currentTime }
        val relay = RelayBudget(RELAY_BYTES_PER_SECOND)
        val frame = RelayCodec.MAX_MESSAGE_BYTES
        repeat(200) {
            bucket.acquire(frame)
            assertTrue(relay.spend(frame.toDouble(), currentTime), "frame $it at $currentTime ms was over budget")
        }
        assertTrue(currentTime < 200L * frame * 1_000L / (RELAY_BYTES_PER_SECOND.toLong() / 2))
    }

    @Test
    fun `the pace follows the limits of the welcome`() {
        val pace = RelayPace.of(RelayLimits())
        assertTrue(pace.messagesPerSecond == 40.0 && pace.messageBurst == 20.0)
        assertTrue(pace.bytesPerSecond == 1.75 * 1024 * 1024 && pace.byteBurst == 1024.0 * 1024)
        val slow = RelayPace.of(RelayLimits(messagesPerSecond = 1, bytesPerSecond = 65_536))
        assertTrue(slow.messagesPerSecond < 1.0 && slow.messageBurst == 1.0)
        assertTrue(slow.bytesPerSecond < 65_536.0)
    }

    private companion object {
        /** The relay's defaults, `RELAY_MSGS_PER_SEC` and `RELAY_BYTES_PER_SEC`. */
        const val RELAY_MESSAGES_PER_SECOND = 50.0
        const val RELAY_BYTES_PER_SECOND = 2.0 * 1024 * 1024
    }
}
