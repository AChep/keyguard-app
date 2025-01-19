package com.artemchep.keyguard.platform

import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.Flow

expect fun recordException(e: Throwable)

expect fun recordLog(message: String)

inline fun recordLogDebug(messageProvider: () -> String) {
    if (!isRelease) {
        val message = messageProvider()
        recordLog(message)
    }
}

expect fun crashlyticsIsEnabled(): Boolean?

expect fun crashlyticsIsEnabledFlow(): Flow<Boolean?>

expect fun crashlyticsSetEnabled(enabled: Boolean?)
