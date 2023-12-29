@file:Suppress("PackageDirectoryMismatch")

package com.google.firebase.crashlytics

import com.artemchep.keyguard.common.util.flow.EventFlow
import com.google.firebase.crashlytics.internal.common.CrashlyticsCore
import com.google.firebase.crashlytics.internal.common.DataCollectionArbiter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private val refreshSink = EventFlow<Unit>()

fun FirebaseCrashlytics.setEnabled(enabled: Boolean?) {
    setCrashlyticsCollectionEnabled(enabled)
    // Ask firebase analytics flow
    // to refresh itself.
    refreshSink.emit(Unit)
}

fun FirebaseCrashlytics.isEnabled(): Boolean? {
    val arbiter = kotlin.runCatching {
        CrashlyticsCore::class.java.getDeclaredField("dataCollectionArbiter")
            .apply {
                isAccessible = true
            }
            .get(core) as DataCollectionArbiter
    }.getOrElse {
        // We have failed to obtain a data collection arbiter, something
        // must have changed in the internal structure.
        recordException(it)
        return null
    }
    return arbiter.isAutomaticDataCollectionEnabled
}

fun FirebaseCrashlytics.isEnabledFlow() = refreshSink
    .onStart {
        emit(Unit)
    }
    .map { isEnabled() }
    .distinctUntilChanged()
