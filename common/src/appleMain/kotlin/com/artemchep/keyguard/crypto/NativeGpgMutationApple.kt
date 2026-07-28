package com.artemchep.keyguard.crypto

import platform.posix.usleep

internal actual fun nativeGpgWaitForClock(milliseconds: Long): Boolean {
    val microseconds = milliseconds.coerceIn(0L, UInt.MAX_VALUE.toLong() / 1_000L) * 1_000L
    return usleep(microseconds.toUInt()) == 0
}
