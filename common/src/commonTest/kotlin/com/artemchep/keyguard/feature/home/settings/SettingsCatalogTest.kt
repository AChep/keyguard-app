package com.artemchep.keyguard.feature.home.settings

import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_autofill_windows_text
import com.artemchep.keyguard.res.browser_extension_title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsCatalogTest {
    @Test
    fun `native iOS preserves route identifiers and ordering`() {
        val items = settingsCatalog(
            config = FlavorConfig(isFreeAsBeer = false),
            platform = Platform.Mobile.Ios.Native,
            release = true,
            includeDeveloper = false,
            includeAutomaticBackups = true,
        )
        assertEquals(
            listOf(
                "section.premium", "subscription", "section.options", "autofill", "security",
                "automatic_backups", "app_link", "watchtower", "display", "about",
            ),
            items.map { it.id },
        )
    }

    @Test
    fun `release watches hide unavailable features and debug overrides remain available`() {
        val platform = Platform.Mobile.Android(isChromebook = false, isWatch = true, sdk = 35)
        fun ids(release: Boolean) = settingsCatalog(
            config = FlavorConfig(isFreeAsBeer = true),
            platform = platform,
            release = release,
        ).map { it.id }

        val releaseIds = ids(true)
        assertFalse("subscription" in releaseIds)
        assertFalse("section.premium" in releaseIds)
        assertFalse("autofill" in releaseIds)
        assertFalse("automatic_backups" in releaseIds)
        assertFalse("debug" in releaseIds)
        val debugIds = ids(false)
        assertTrue(debugIds.containsAll(listOf("autofill", "automatic_backups", "debug", "notifications")))
        // A watch never links its app.
        assertFalse("app_link" in releaseIds)
        assertFalse("app_link" in debugIds)
    }

    @Test
    fun `release Windows offers autofill settings for its browser extension and passkey provider`() {
        val items = settingsCatalog(
            config = FlavorConfig(isFreeAsBeer = true),
            platform = Platform.Desktop.Windows,
            release = true,
        )
        val autofill = items.filterIsInstance<SettingsCatalogItem.Action>()
            .single { it.destination == SettingsDestination.AUTOFILL }
        assertEquals(Res.string.browser_extension_autofill_windows_text, autofill.text)
    }

    @Test
    fun `release desktop apps offer autofill settings for the browser extension`() {
        val platforms = listOf(
            Platform.Desktop.Linux(isFlatpak = false),
            Platform.Desktop.Linux(isFlatpak = true),
            Platform.Desktop.MacOS.Jvm,
        )
        platforms.forEach { platform ->
            val autofill = settingsCatalog(
                config = FlavorConfig(isFreeAsBeer = true),
                platform = platform,
                release = true,
            ).filterIsInstance<SettingsCatalogItem.Action>()
                .single { it.destination == SettingsDestination.AUTOFILL }
            assertEquals(Res.string.browser_extension_title, autofill.text)
        }
        // An unknown desktop has no browser extension.
        val other = settingsCatalog(
            config = FlavorConfig(isFreeAsBeer = true),
            platform = Platform.Desktop.Other,
            release = true,
        )
        assertFalse(other.any { it.id == "autofill" })
    }

    @Test
    fun `desktop offers premium for web purchases unless the flavor is free`() {
        val platforms = listOf(
            Platform.Desktop.Linux(isFlatpak = false),
            Platform.Desktop.Windows,
            Platform.Desktop.MacOS.Jvm,
        )
        platforms.forEach { platform ->
            fun ids(free: Boolean) = settingsCatalog(
                config = FlavorConfig(isFreeAsBeer = free),
                platform = platform,
                release = true,
            ).map { it.id }

            assertEquals(listOf("section.premium", "subscription"), ids(free = false).take(2))
            assertFalse("subscription" in ids(free = true))
        }
    }

    @Test
    fun `host capability overrides only remove their own routes`() {
        val items = settingsCatalog(
            config = FlavorConfig(isFreeAsBeer = false),
            platform = Platform.Desktop.MacOS.Native,
            release = true,
            includeAutofill = false,
            includeWatchtower = false,
            includeDeveloper = false,
            includeAutomaticBackups = false,
            includeAppLink = false,
        )
        assertEquals(listOf("section.options", "security", "display", "about"), items.map { it.id })
    }
}
