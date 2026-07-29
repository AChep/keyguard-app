package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.platform.util.isRelease
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.crashlytics.isEnabled
import com.google.firebase.crashlytics.isEnabledFlow
import com.google.firebase.crashlytics.setEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.net.SocketException
import java.net.UnknownHostException

actual fun recordException(e: Throwable) {
    if (
        e is NoAnalytics ||
        e is UnknownHostException ||
        e is SocketException
    ) {
        return
    }
    // Dropping the report is the whole of the degraded behaviour: the caller
    // has already recovered from [e] and never branches on it being recorded.
    crashlyticsOrElse(fallback = Unit) {
        Firebase.crashlytics.recordException(e)
    }
    if (!isRelease) {
        e.printStackTrace()
    }
}

actual fun recordLog(message: String) {
    // A breadcrumb; losing it costs a line of context in a report that is not
    // being written either.
    crashlyticsOrElse(fallback = Unit) {
        Firebase.crashlytics.log(message)
    }
}

actual fun crashlyticsIsEnabled(): Boolean? =
    // `null` is the established "collection state is unknown" answer — the
    // reflective lookup inside `FirebaseCrashlytics.isEnabled` already returns
    // it — and every caller falls back to its own default.
    crashlyticsOrElse<Boolean?>(fallback = null) {
        Firebase.crashlytics.isEnabled()
    }

actual fun crashlyticsIsEnabledFlow(): Flow<Boolean?> =
    // The same unknown, as a flow. The settings switch renders `null` as off,
    // which is the truth when there is no reporter to collect anything.
    crashlyticsOrElse(fallback = flowOf<Boolean?>(null)) {
        Firebase.crashlytics.isEnabledFlow()
    }

actual fun crashlyticsSetEnabled(enabled: Boolean?) {
    // Safe to drop even when the user is opting *out*: the only way this fails
    // is the SDK not resolving, and a Crashlytics that cannot be reached is
    // also not collecting anything to opt out of.
    crashlyticsOrElse(fallback = Unit) {
        Firebase.crashlytics.setEnabled(enabled)
    }
}

/**
 * Runs a Crashlytics call, degrading a failure *of the reporter itself* into
 * [fallback].
 *
 * Every entry point in this file is reached from a recovery path — the
 * canonical caller is a `getOrElse` that has just turned a throwable into a
 * value — so a throw from the reporter replaces the caller's recovered value
 * with a crash it never accounted for. And the reporter does throw:
 * `Firebase.crashlytics` resolves the `FirebaseApp` singleton, which fails
 * outright when no `google-services` backend is configured and on the JVM
 * unit-test classpath, where `android.os.Process` is not mocked.
 *
 * An [Error] and a [kotlinx.coroutines.CancellationException] still propagate;
 * neither one is "the reporter is unavailable".
 */
private fun <T> crashlyticsOrElse(
    fallback: T,
    block: () -> T,
): T = runCatchingNonFatal(block).getOrDefault(fallback)
