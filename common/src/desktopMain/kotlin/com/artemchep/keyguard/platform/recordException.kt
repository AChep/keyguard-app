package com.artemchep.keyguard.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual fun recordException(e: Throwable) {
    e.printStackTrace()
}

actual fun recordLog(message: String) {
    println(message)
}

actual fun crashlyticsIsEnabled(): Boolean? {
    return false
}

actual fun crashlyticsIsEnabledFlow(): Flow<Boolean?> {
    return flowOf(false)
}

actual fun crashlyticsSetEnabled(enabled: Boolean?) {
}
