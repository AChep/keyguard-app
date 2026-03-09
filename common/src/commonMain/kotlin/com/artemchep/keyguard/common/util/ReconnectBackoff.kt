package com.artemchep.keyguard.common.util

import arrow.core.throwIfFatal
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

internal class ReconnectBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val multiplier: Double = DEFAULT_MULTIPLIER,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    private val provideRandomDouble: () -> Double = { Random.nextDouble() },
) {
    companion object {
        const val DEFAULT_BASE_DELAY_MS: Long = 1_000L
        const val DEFAULT_MULTIPLIER: Double = 2.0
        const val DEFAULT_MAX_DELAY_MS: Long = 60_000L
        const val DEFAULT_JITTER_RATIO: Double = 0.2
    }

    @Volatile
    private var _attempt: Int = 0

    val attempt: Int
        get() = _attempt

    fun nextDelayMs(): Long {
        val exponentialDelay = baseDelayMs.toDouble() * multiplier.pow(_attempt.toDouble())
        val clampedDelay = exponentialDelay.coerceAtMost(maxDelayMs.toDouble())
        val jitteredDelay = clampedDelay * jitterMultiplier()
        _attempt += 1
        return jitteredDelay.roundToLong().coerceAtLeast(0L)
    }

    fun reset() {
        _attempt = 0
    }

    private fun jitterMultiplier(): Double {
        val random = provideRandomDouble()
            .coerceIn(0.0, 1.0)
        val normalized = (random * 2.0) - 1.0
        return 1.0 + normalized * jitterRatio
    }
}

internal class ReconnectFatalException(e: Throwable) : RuntimeException(e)

internal suspend inline fun ReconnectBackoff.withRunForever(
    crossinline block: suspend ReconnectBackoff.() -> Unit,
) {
    while (true) {
        // The loop is intentionally endless: reconnect on every
        // disconnect/start failure until the coroutine is canceled.
        runCatching {
            block()
        }.onFailure { e ->
            if (e is CancellationException || e is ReconnectFatalException) {
                throw e
            } else e.throwIfFatal()

            recordException(e)
        }

        delay(nextDelayMs())
    }
}
