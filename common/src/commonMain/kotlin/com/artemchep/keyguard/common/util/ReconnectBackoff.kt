package com.artemchep.keyguard.common.util

import arrow.core.throwIfFatal
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

@OptIn(ExperimentalAtomicApi::class)
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

    private val attemptRef = AtomicInt(0)

    val attempt: Int
        get() = attemptRef.load()

    fun nextDelayMs(): Long {
        val attempt = attemptRef.fetchAndAdd(1)
        val exponentialDelay = baseDelayMs.toDouble() * multiplier.pow(attempt.toDouble())
        val clampedDelay = exponentialDelay.coerceAtMost(maxDelayMs.toDouble())
        val jitteredDelay = clampedDelay * jitterMultiplier()
        return jitteredDelay.roundToLong().coerceAtLeast(0L)
    }

    fun reset() {
        attemptRef.store(0)
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
