package com.artemchep.keyguard.util.launchatlogin

import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class NativeLaunchAtLoginTest {
    @Test
    fun validatesCBridgeWithoutChangingRegistration() {
        assertEquals(-1, NativeLaunchAtLogin.execute(99, ""))
        assertNotNull(NativeLaunchAtLogin.lastError())
        // Login detection takes an empty request; registration does not.
        assertEquals(0, NativeLaunchAtLogin.execute(5, ""))
        assertEquals(-1, NativeLaunchAtLogin.execute(0, ""))
    }

    @Test
    fun validLinuxRequestIsUnavailable() {
        // The bridge parses the full request, then reports the foreign target as unsupported.
        val launcher = NSTemporaryDirectory().trimEnd('/') + "/Keyguard fixture/launcher"
        val launchAtLogin = LaunchAtLogin(
            LaunchAtLoginTarget.XdgAutostart(
                id = "com.example.fixture",
                name = "Fixture",
                executable = launcher,
                arguments = listOf("--launch-at-login"),
            ),
        )
        assertEquals(LaunchAtLoginStatus.UNAVAILABLE, launchAtLogin.status())
        val failure = assertFailsWith<LaunchAtLoginException> { launchAtLogin.setEnabled(true) }
        assertEquals(LaunchAtLoginError.UNAVAILABLE, failure.code)
    }
}
