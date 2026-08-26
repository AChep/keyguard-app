package com.artemchep.keyguard.common.util

internal actual fun sleepBlocking(milliseconds: Long): Boolean {
    require(milliseconds >= 0L) { "Sleep duration must not be negative." }
    return try {
        Thread.sleep(milliseconds)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
