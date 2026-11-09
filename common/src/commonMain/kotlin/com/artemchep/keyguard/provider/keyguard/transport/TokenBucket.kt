package com.artemchep.keyguard.provider.keyguard.transport

import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.min

/**
 * The pace of a relay connection: a little under the budgets of its [RelayLimits], so that
 * network jitter never pushes a burst over them. Over budget, the relay stops reading the
 * connection, which holds up its pongs and every room on it.
 */
internal data class RelayPace(
    val messagesPerSecond: Double,
    val messageBurst: Double,
    val bytesPerSecond: Double,
    val byteBurst: Double,
) {
    companion object {
        private const val RATE_MARGIN = 0.8
        private const val BYTE_RATE_MARGIN = 0.875
        private const val BURST_SHARE = 0.4
        private const val BYTE_BURST_SHARE = 0.5

        /** 40 messages and 1.75 MiB per second, with bursts of 20 and 1 MiB, for the defaults. */
        fun of(limits: RelayLimits): RelayPace {
            val messages = limits.messagesPerSecond.toDouble()
            val bytes = limits.bytesPerSecond.toDouble()
            return RelayPace(
                messagesPerSecond = messages * RATE_MARGIN,
                messageBurst = (messages * BURST_SHARE).coerceAtLeast(1.0),
                bytesPerSecond = bytes * BYTE_RATE_MARGIN,
                byteBurst = bytes * BYTE_BURST_SHARE,
            )
        }
    }
}

/**
 * Two token buckets, one for messages and one for bytes, mirroring the relay's own rate
 * limiter. Not thread-safe: one writer spends from it.
 */
internal class TokenBucket(
    private val limits: RelayPace,
    private val nowMs: () -> Long,
) {
    private var messages = limits.messageBurst
    private var bytes = limits.byteBurst
    private var updatedAt = nowMs()

    /** Suspends until one message of [size] bytes fits the budgets, then spends them. */
    suspend fun acquire(size: Int) {
        val cost = min(size.toDouble(), limits.byteBurst)
        while (true) {
            refill()
            if (messages >= 1.0 && bytes >= cost) {
                messages -= 1.0
                bytes -= cost
                return
            }
            val waitMessages = (1.0 - messages).coerceAtLeast(0.0) / limits.messagesPerSecond
            val waitBytes = (cost - bytes).coerceAtLeast(0.0) / limits.bytesPerSecond
            delay(ceil(maxOf(waitMessages, waitBytes) * MILLIS_PER_SECOND).toLong().coerceAtLeast(1L))
        }
    }

    private fun refill() {
        val now = nowMs()
        val seconds = (now - updatedAt).coerceAtLeast(0L) / MILLIS_PER_SECOND
        updatedAt = now
        messages = min(limits.messageBurst, messages + seconds * limits.messagesPerSecond)
        bytes = min(limits.byteBurst, bytes + seconds * limits.bytesPerSecond)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
