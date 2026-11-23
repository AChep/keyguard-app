package com.artemchep.keyguard.presentation.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLaunchReasonTest {
    @Test
    fun explicitLaunchAlwaysShowsAndActivates() {
        val reason = DesktopLaunchReason.fromArguments(emptyList())
        assertTrue(reason.activatesExistingInstance)
        assertEquals(true, reason.showsMainWindow(startInBackground = true))
    }

    @Test
    fun loginWaitsForPreferenceWithoutActivatingAnExistingOwner() {
        val reason = DesktopLaunchReason.fromArguments(listOf("--launch-at-login"))
        assertFalse(reason.activatesExistingInstance)
        assertNull(reason.showsMainWindow(startInBackground = null))
        assertEquals(false, reason.showsMainWindow(startInBackground = true))
        assertEquals(true, reason.showsMainWindow(startInBackground = false))
        assertEquals(reason, DesktopLaunchReason.fromArguments(emptyList(), loginActivation = true))
    }

    @Test
    fun explicitBackgroundOverridesLoginPreference() {
        val reason = DesktopLaunchReason.fromArguments(listOf("--launch-at-login", "--background"))
        assertEquals(DesktopLaunchReason.BACKGROUND, reason)
        assertFalse(reason.activatesExistingInstance)
        assertEquals(false, reason.showsMainWindow(startInBackground = false))
    }

    @Test
    fun passkeyRequestKeepsItsOwnLifetime() {
        val reason = DesktopLaunchReason.fromArguments(listOf("--background"), passkeyActivation = true)
        assertEquals(DesktopLaunchReason.PASSKEY, reason)
        assertEquals(false, reason.showsMainWindow(startInBackground = false))
    }

    @Test
    fun onlyUnattendedLaunchesOutliveTheirWindow() {
        val unattended = setOf(DesktopLaunchReason.LOGIN, DesktopLaunchReason.BACKGROUND)
        for (reason in DesktopLaunchReason.entries) {
            assertEquals(reason in unattended, reason.keepsProcessAlive, reason.name)
            assertEquals(reason != DesktopLaunchReason.USER, reason.locksWithoutWindow, reason.name)
        }
    }
}
