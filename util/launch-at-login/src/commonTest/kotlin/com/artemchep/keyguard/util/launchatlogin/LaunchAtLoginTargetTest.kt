package com.artemchep.keyguard.util.launchatlogin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LaunchAtLoginTargetTest {
    @Test
    fun preservesArgumentsWithoutShellQuoting() {
        val target = LaunchAtLoginTarget.XdgAutostart(
            id = "com.example.fixture",
            name = "Fixture",
            executable = "/opt/应用 with spaces/launcher",
            arguments = listOf("", "--launch-at-login", "literal $ & %"),
        )
        assertEquals(
            listOf(
                "xdg",
                "com.example.fixture",
                "Fixture",
                "/opt/应用 with spaces/launcher",
                // The startup task ID is empty outside Windows.
                "",
                "",
                "--launch-at-login",
                "literal $ & %",
            ),
            target.encode().split('\u0000'),
        )
    }

    @Test
    fun windowsCarriesItsStartupTaskBeforeArguments() {
        val target = LaunchAtLoginTarget.WindowsStartup(
            id = "com.example.fixture",
            name = "Fixture",
            executable = "C:\\Fixture\\Fixture.exe",
            taskId = "FixtureStartup",
            arguments = listOf("--launch-at-login"),
        )
        assertEquals(
            listOf(
                "windows",
                "com.example.fixture",
                "Fixture",
                "C:\\Fixture\\Fixture.exe",
                "FixtureStartup",
                "--launch-at-login",
            ),
            target.encode().split('\u0000'),
        )
    }

    @Test
    fun macosAndFlatpakSendTheFullHeader() {
        assertEquals(listOf("macos", "", "", "", ""), LaunchAtLoginTarget.MacOSMainApp.encode().split('\u0000'))
        assertEquals(
            listOf("flatpak", "", "Reason", "", "", "keyguard", "--x"),
            LaunchAtLoginTarget.FlatpakBackground(listOf("keyguard", "--x"), "Reason").encode().split('\u0000'),
        )
    }

    @Test
    fun rejectsProtocolSeparatorsAndOversizedRequests() {
        assertFailsWith<IllegalArgumentException> {
            LaunchAtLoginTarget.WindowsStartup("id", "Name", "C:\\app.exe", taskId = "bad\u0000id").encode()
        }
        assertFailsWith<IllegalArgumentException> {
            LaunchAtLoginTarget.FlatpakBackground(listOf("a".repeat(65_537)), "Reason").encode()
        }
    }
}
