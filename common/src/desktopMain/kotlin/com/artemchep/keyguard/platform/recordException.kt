package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.model.NoAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual fun recordException(e: Throwable) {
    if (
        e is NoAnalytics
    ) {
        return
    }
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
