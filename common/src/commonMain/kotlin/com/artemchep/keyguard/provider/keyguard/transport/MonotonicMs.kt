package com.artemchep.keyguard.provider.keyguard.transport

import kotlin.time.TimeSource

/** Returns a monotonic clock in milliseconds, counted from the call. */
internal fun monotonicMs(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
