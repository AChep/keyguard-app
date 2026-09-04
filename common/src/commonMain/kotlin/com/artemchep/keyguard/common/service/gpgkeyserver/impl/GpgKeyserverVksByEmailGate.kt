package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Serializes VKS by-email lookups and spaces them at least [intervalMillis]
 * apart, waiting (never skipping or erroring) when a call arrives too soon
 * after the previous one.
 *
 * Successful results are cached for [intervalMillis] and served without
 * waiting, so a repeated lookup (e.g. the user verifying the same key twice in
 * a row) does not queue behind the rate limit. Failures are never cached.
 */
internal class GpgKeyserverVksByEmailGate<T>(
    private val intervalMillis: Long,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Entry<T>(
        val value: T,
        val atMillis: Long,
    )

    // Guards the state below. Accessed only under the mutex.
    private val mutex = Mutex()
    private var lastAtMillis: Long? = null
    private val cache = mutableMapOf<String, Entry<T>>()

    suspend fun execute(
        key: String,
        block: suspend () -> T,
    ): T = mutex.withLock {
        val startedAt = now()
        cache.values.removeAll { entry ->
            startedAt - entry.atMillis >= intervalMillis
        }
        cache[key]?.let { entry ->
            return@withLock entry.value
        }

        val last = lastAtMillis
        if (last != null) {
            val remaining = intervalMillis - (startedAt - last)
            if (remaining > 0L) {
                delay(remaining)
            }
        }

        try {
            val value = block()
            cache[key] = Entry(
                value = value,
                atMillis = now(),
            )
            value
        } finally {
            lastAtMillis = now()
        }
    }
}
