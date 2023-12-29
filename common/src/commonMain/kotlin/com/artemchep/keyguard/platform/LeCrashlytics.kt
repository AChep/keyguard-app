package com.artemchep.keyguard.platform

import kotlinx.coroutines.flow.Flow

expect fun recordException(e: Throwable)

expect fun recordLog(message: String)

expect fun crashlyticsIsEnabled(): Boolean?

expect fun crashlyticsIsEnabledFlow(): Flow<Boolean?>

expect fun crashlyticsSetEnabled(enabled: Boolean?)
