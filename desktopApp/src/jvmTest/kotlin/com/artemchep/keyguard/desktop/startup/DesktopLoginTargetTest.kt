package com.artemchep.keyguard.desktop.startup

import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DesktopLoginTargetTest {
    @Test
    fun appImageRegistersPersistentImageNotTemporaryMount() {
        val target = assertIs<LaunchAtLoginTarget.XdgAutostart>(target(
            appImage = "/home/user/Applications/Keyguard.AppImage",
            isAppImageMount = true,
        ))
        assertEquals("/home/user/Applications/Keyguard.AppImage", target.executable)
        assertEquals(listOf("--launch-at-login"), target.arguments)
        assertNull(target(isAppImageMount = true))
    }

    @Test
    fun flatpakUsesPortalCommandInsideItsSandbox() {
        val target = assertIs<LaunchAtLoginTarget.FlatpakBackground>(target(flatpakId = "com.artemchep.keyguard"))
        assertEquals(listOf("Keyguard", "--launch-at-login"), target.command)
    }

    @Test
    fun windowsCarriesBothTheShortcutAndTheManifestTask() {
        val target = assertIs<LaunchAtLoginTarget.WindowsStartup>(
            target(os = "Windows 11", launcher = "C:\\Program Files\\Keyguard\\Keyguard.exe"),
        )
        assertEquals("C:\\Program Files\\Keyguard\\Keyguard.exe", target.executable)
        assertEquals("KeyguardStartup", target.taskId)
        assertEquals(listOf("--launch-at-login"), target.arguments)
    }

    @Test
    fun developmentOrUnknownPlatformCannotRegisterJavaAsApplication() {
        assertNull(target(launcher = null))
        assertNull(target(os = "Mac OS X", launcher = null))
        assertNull(target(os = "Other"))
    }

    private fun target(
        os: String = "Linux",
        launcher: String? = "/tmp/.mount_Keyguard/usr/bin/Keyguard",
        appImage: String? = null,
        isAppImageMount: Boolean = false,
        flatpakId: String? = null,
    ) = desktopLoginTarget(
        osName = os,
        launcher = launcher,
        appImage = appImage,
        isAppImageMount = isAppImageMount,
        flatpakId = flatpakId,
        reason = "Test",
    )
}
