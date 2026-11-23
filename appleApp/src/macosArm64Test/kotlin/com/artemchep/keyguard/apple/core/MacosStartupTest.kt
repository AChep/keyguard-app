package com.artemchep.keyguard.apple.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacosStartupTest {
    @Test
    fun loginAndBackgroundDuplicatesDoNotActivateTheOwner() {
        assertFalse(MacosStartup(emptyList(), loginActivation = true).activatesExistingInstance)
        assertFalse(MacosStartup(listOf("--background"), loginActivation = false).activatesExistingInstance)
        assertTrue(MacosStartup(emptyList(), loginActivation = false).activatesExistingInstance)
    }

    @Test
    fun onlyLoginWaitsForStartupPreferences() {
        assertTrue(MacosStartup(emptyList(), loginActivation = true).needsStartupPreferences)
        assertFalse(MacosStartup(emptyList(), loginActivation = false).needsStartupPreferences)
        assertFalse(MacosStartup(listOf("--background"), loginActivation = false).needsStartupPreferences)
    }
}
