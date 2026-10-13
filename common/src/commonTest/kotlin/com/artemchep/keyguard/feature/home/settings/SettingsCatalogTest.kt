package com.artemchep.keyguard.feature.home.settings

import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkeys
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
                "automatic_backups", "watchtower", "display", "about",
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
    }

    @Test
    fun `release Windows offers autofill settings for its passkey provider`() {
        val items = settingsCatalog(
            config = FlavorConfig(isFreeAsBeer = true),
            platform = Platform.Desktop.Windows,
            release = true,
        )
        val autofill = items.filterIsInstance<SettingsCatalogItem.Action>()
            .single { it.destination == SettingsDestination.AUTOFILL }
        assertEquals(Res.string.passkeys, autofill.text)
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
        )
        assertEquals(listOf("section.options", "security", "display", "about"), items.map { it.id })
    }
}
