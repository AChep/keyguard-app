package com.artemchep.keyguard.util.launchatlogin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class NativeLaunchAtLoginTest {
    @Test
    fun rejectsInvalidRequestsWithoutChangingRegistration() {
        assertEquals(-1, NativeLaunchAtLogin.execute(99, ""))
        assertNotNull(NativeLaunchAtLogin.lastError())
        // Login detection takes an empty request; registration does not.
        assertEquals(0, NativeLaunchAtLogin.execute(5, ""))
        assertEquals(-1, NativeLaunchAtLogin.execute(0, ""))
        assertEquals(-1, NativeLaunchAtLogin.execute(0, "invalid"))
    }

    @Test
    fun validRequestForAnotherOperatingSystemIsUnavailable() {
        // The bridge parses the full request, then reports the foreign target as unsupported.
        val launcher = File(System.getProperty("java.io.tmpdir"), "Keyguard fixture/launcher").absolutePath
        val linux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
        val target = if (linux) {
            LaunchAtLoginTarget.WindowsStartup(
                id = "com.example.fixture",
                name = "Fixture",
                executable = launcher,
                taskId = "FixtureStartup",
                arguments = listOf("--launch-at-login"),
            )
        } else {
            LaunchAtLoginTarget.XdgAutostart(
                id = "com.example.fixture",
                name = "Fixture",
                executable = launcher,
                arguments = listOf("--launch-at-login"),
            )
        }
        val launchAtLogin = LaunchAtLogin(target)
        assertEquals(LaunchAtLoginStatus.UNAVAILABLE, launchAtLogin.status())
        val failure = assertFailsWith<LaunchAtLoginException> { launchAtLogin.setEnabled(true) }
        assertEquals(LaunchAtLoginError.UNAVAILABLE, failure.code)
    }
}
