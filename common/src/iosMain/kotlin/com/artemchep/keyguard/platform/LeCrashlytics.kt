package com.artemchep.keyguard.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual fun recordException(e: Throwable) {
}

actual fun recordLog(message: String) {
}

actual fun crashlyticsIsEnabled(): Boolean? = null

actual fun crashlyticsIsEnabledFlow(): Flow<Boolean?> = flowOf(null)

actual fun crashlyticsSetEnabled(enabled: Boolean?) {
}
