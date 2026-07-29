package com.artemchep.keyguard.platform

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The Crashlytics actuals run on a classpath where `Firebase.crashlytics`
 * throws — `FirebaseApp.getInstance` reaches the unmocked `android.os.Process`
 * — which is the same shape as a build with no `google-services` backend. Every
 * one of them has to answer anyway: they are called from recovery paths that
 * have already turned a throwable into a value.
 */
class RecordExceptionTest {
    @Test
    fun `recordException does not propagate a reporter failure`() {
        recordException(IllegalStateException("reported failure"))
    }

    @Test
    fun `recordLog does not propagate a reporter failure`() {
        recordLog("breadcrumb")
    }

    @Test
    fun `crashlyticsIsEnabled degrades to unknown`() {
        assertNull(crashlyticsIsEnabled())
    }

    @Test
    fun `crashlyticsIsEnabledFlow degrades to unknown`() = runTest {
        assertNull(crashlyticsIsEnabledFlow().first())
    }

    @Test
    fun `crashlyticsSetEnabled does not propagate a reporter failure`() {
        crashlyticsSetEnabled(false)
    }
}
