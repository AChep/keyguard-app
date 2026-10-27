package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.yubikey_error_title
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatpakDeviceAccessTest {
    private val flatpak = Platform.Desktop.Linux(isFlatpak = true)

    @Test
    fun `manifest device access is granted`() {
        assertTrue(hasSecurityKeyAccess(flatpakInfo(devices = "dri;all;")))
    }

    @Test
    fun `revoked device access is reported`() {
        assertFalse(hasSecurityKeyAccess(flatpakInfo(devices = "dri;")))
        assertFalse(hasSecurityKeyAccess(flatpakInfo(devices = null)))
    }

    @Test
    fun `narrower device classes do not expose hidraw`() {
        assertFalse(hasSecurityKeyAccess(flatpakInfo(devices = "dri;input;usb;")))
    }

    @Test
    fun `devices outside the context group are ignored`() {
        val info = "[Application]\ndevices=all;\n\n[Context]\nshared=network;\n"
        assertFalse(flatpakInfoGrantsAllDevices(info))
    }

    @Test
    fun `unreadable sandbox metadata lets the native client try`() {
        assertTrue(hasSecurityKeyAccess(File("/nonexistent/.flatpak-info")))
    }

    @Test
    fun `builds outside flatpak are not checked`() = runTest {
        val revoked = flatpakInfo(devices = "dri;")
        for (platform in listOf(Platform.Desktop.Linux.native, Platform.Desktop.Windows)) {
            requireSecurityKeyAccess(Res.string.yubikey_error_title, platform, revoked)
        }
    }

    @Test
    fun `revoked device access explains how to restore it`() = runTest {
        val error = assertFails {
            requireSecurityKeyAccess(
                title = Res.string.yubikey_error_title,
                platform = flatpak,
                flatpakInfo = flatpakInfo(devices = "dri;"),
                appId = "com.artemchep.keyguard.Devel",
            )
        }
        val readable = assertIs<Readable>(error)
        assertEquals(TextHolder.Res(Res.string.yubikey_error_title), readable.title)
        val text = assertIs<TextHolder.Value>(readable.text).data
        assertTrue(text.endsWith("\nflatpak override --user --device=all com.artemchep.keyguard.Devel"), text)
    }

    @Test
    fun `granted device access shows the prompt`() = runTest {
        requireSecurityKeyAccess(
            title = Res.string.yubikey_error_title,
            platform = flatpak,
            flatpakInfo = flatpakInfo(devices = "all;"),
        )
    }

    private fun flatpakInfo(devices: String?): File {
        val file = Files.createTempFile("flatpak-info", null).toFile()
        file.deleteOnExit()
        val context = listOfNotNull(
            "[Context]",
            "shared=network;ipc;",
            "sockets=x11;",
            devices?.let { "devices=$it" },
        )
        file.writeText(
            (listOf("[Application]", "name=com.artemchep.keyguard", "") + context)
                .joinToString("\n"),
        )
        return file
    }
}
