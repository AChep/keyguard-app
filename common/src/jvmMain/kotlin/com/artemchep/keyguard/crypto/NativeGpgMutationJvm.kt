package com.artemchep.keyguard.crypto

internal actual fun nativeGpgWaitForClock(milliseconds: Long): Boolean = try {
    Thread.sleep(milliseconds)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}
