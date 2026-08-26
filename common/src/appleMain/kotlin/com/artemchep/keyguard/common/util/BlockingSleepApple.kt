package com.artemchep.keyguard.common.util

import platform.posix.usleep

internal actual fun sleepBlocking(milliseconds: Long): Boolean {
    require(milliseconds >= 0L) { "Sleep duration must not be negative." }

    var remainingMilliseconds = milliseconds
    while (remainingMilliseconds > 0L) {
        val chunkMilliseconds = remainingMilliseconds.coerceAtMost(MAX_SLEEP_CHUNK_MILLISECONDS)
        val result = usleep((chunkMilliseconds * MICROSECONDS_PER_MILLISECOND).toUInt())
        if (result != 0) return false
        remainingMilliseconds -= chunkMilliseconds
    }
    return true
}

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
private const val MAX_SLEEP_CHUNK_MILLISECONDS = 999L
