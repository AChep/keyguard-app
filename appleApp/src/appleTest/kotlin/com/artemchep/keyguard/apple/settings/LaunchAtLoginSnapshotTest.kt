package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.service.startup.LaunchAtLoginState
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchAtLoginSnapshotTest {
    @Test
    fun registeredPendingApprovalOffersSystemSettings() {
        val snapshot = LaunchAtLoginState(LaunchAtLoginStatus.REQUIRES_APPROVAL)
            .toLaunchAtLoginSnapshot(null)
        assertTrue(snapshot.enabled)
        assertTrue(snapshot.requiresApproval)
        assertTrue(snapshot.available)
        assertTrue(snapshot.canOpenSettings)
    }

    @Test
    fun externallyDisabledDoesNotReportEnabled() {
        val snapshot = LaunchAtLoginState(LaunchAtLoginStatus.DISABLED_BY_USER)
            .toLaunchAtLoginSnapshot("Disabled in system settings")
        assertFalse(snapshot.enabled)
        assertFalse(snapshot.available)
        assertTrue(snapshot.canOpenSettings)
        assertEquals("Disabled in system settings", snapshot.message)
    }

    @Test
    fun failedOperationPreservesEffectiveStateAndReportsFailure() {
        val snapshot = LaunchAtLoginState(LaunchAtLoginStatus.ENABLED, busy = false, failed = true)
            .toLaunchAtLoginSnapshot("Could not update launch at login")
        assertTrue(snapshot.enabled)
        assertEquals("Could not update launch at login", snapshot.message)
        assertFalse(snapshot.busy)
        assertTrue(snapshot.failed)
    }

    @Test
    fun unreadAndUnavailableDoNotOfferToggle() {
        for (status in listOf(null, LaunchAtLoginStatus.UNAVAILABLE)) {
            val snapshot = LaunchAtLoginState(status).toLaunchAtLoginSnapshot(null)
            assertFalse(snapshot.available)
            assertFalse(snapshot.enabled)
            assertFalse(snapshot.canOpenSettings)
        }
    }

    @Test
    fun settingsHideTheOptionWhenUnavailableOrNotReadYet() {
        assertFalse(LaunchAtLoginState(LaunchAtLoginStatus.UNAVAILABLE).toLaunchAtLoginSnapshot(null).shown)
        assertFalse(LaunchAtLoginState(null).toLaunchAtLoginSnapshot(null).shown)
        // A failed first read shows its error and Retry.
        assertTrue(LaunchAtLoginState(null, failed = true).toLaunchAtLoginSnapshot(null).shown)
        for (status in LaunchAtLoginStatus.entries - LaunchAtLoginStatus.UNAVAILABLE) {
            assertTrue(LaunchAtLoginState(status).toLaunchAtLoginSnapshot(null).shown, "$status")
        }
    }

    @Test
    fun unknownIsAnUncheckedChangeableToggle() {
        val snapshot = LaunchAtLoginState(LaunchAtLoginStatus.UNKNOWN)
            .toLaunchAtLoginSnapshot("Keyguard can't read the startup state.")
        assertTrue(snapshot.available)
        assertFalse(snapshot.enabled)
        assertEquals("Keyguard can't read the startup state.", snapshot.message)
    }

    @Test
    fun managedStatePreservesEffectiveCheckedStateWithoutOfferingChanges() {
        for (status in listOf(LaunchAtLoginStatus.ENABLED_BY_POLICY, LaunchAtLoginStatus.DISABLED_BY_POLICY)) {
            val snapshot = LaunchAtLoginState(status).toLaunchAtLoginSnapshot("Managed by system policy")
            assertEquals(status == LaunchAtLoginStatus.ENABLED_BY_POLICY, snapshot.enabled)
            assertFalse(snapshot.available)
            assertFalse(snapshot.canOpenSettings)
            assertEquals("Managed by system policy", snapshot.message)
        }
    }
}
