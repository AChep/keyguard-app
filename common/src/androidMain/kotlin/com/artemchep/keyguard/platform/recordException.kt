package com.artemchep.keyguard.platform

import com.artemchep.keyguard.platform.util.isRelease
import com.google.firebase.crashlytics.isEnabled
import com.google.firebase.crashlytics.isEnabledFlow
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.crashlytics.setEnabled
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.Flow
import java.net.SocketException
import java.net.UnknownHostException

actual fun recordException(e: Throwable) {
    if (
        e is UnknownHostException ||
        e is SocketException
    ) {
        return
    }
    Firebase.crashlytics.recordException(e)
    if (!isRelease) {
        e.printStackTrace()
    }
}

actual fun recordLog(message: String) {
    Firebase.crashlytics.log(message)
}

actual fun crashlyticsIsEnabled(): Boolean? =
    Firebase.crashlytics.isEnabled()

actual fun crashlyticsIsEnabledFlow(): Flow<Boolean?> =
    Firebase.crashlytics.isEnabledFlow()

actual fun crashlyticsSetEnabled(enabled: Boolean?) =
    Firebase.crashlytics.setEnabled(enabled)
