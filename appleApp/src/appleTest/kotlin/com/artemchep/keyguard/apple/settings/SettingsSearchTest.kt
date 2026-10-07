package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.res.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test
    fun titlesRankAheadOfDescriptionsWithStableTiesAndNoDuplicates() {
        val entries = listOf(
            entry("description", "Security", description = "clipboard"),
            entry("contains", "Clear clipboard"),
            entry("prefix", "Clipboard timeout"),
            entry("exact", "Clipboard"),
            entry("tie", "Clipboard timeout"),
            entry("exact", "Duplicate"),
        )
        assertEquals(
            listOf("exact", "prefix", "tie", "contains", "description"),
            SettingsSearchIndex(entries, "en").search("clipboard").map { it.id },
        )
    }

    @Test
    fun normalizesAccentsCaseWhitespaceAndMatchesWordsAcrossFields() {
        val index = SettingsSearchIndex(
            listOf(entry("fr", "Effacer le presse-papiers", path = "Sécurité", keywords = "copier coller")),
            "fr",
        )
        assertEquals(listOf("fr"), index.search("  SECURITE\n COLLER  ").map { it.id })
        assertEquals(listOf("fr"), index.search("se\u0301curite\u0301").map { it.id })
        assertTrue(index.search("\n\t ").isEmpty())
        assertTrue(index.search("coller inconnu").isEmpty())
    }

    @Test
    fun indexesLocalizedTitlesAndUsesTheNativeBiometricLabel() = runTest {
        val entries = catalog(macOS = false, biometric = true) { resource ->
            if (resource == Res.string.pref_item_clipboard_auto_clear_title) "Effacer le presse-papiers" else "Réglages"
        }
        val index = SettingsSearchIndex(entries, "fr")
        assertEquals(SettingsSearchTarget.CLIPBOARD, index.search("presse-papiers").single().target)
        assertEquals("Face ID", entries.single { it.target == SettingsSearchTarget.BIOMETRIC }.title)
    }

    @Test
    fun filtersUnsupportedHardwareAndPlatformSpecificControls() = runTest {
        val ios = catalog(macOS = false)
        assertFalse(ios.any { it.categoryId in setOf("macos_general", "developer") })
        val hardwareTargets = setOf(
            SettingsSearchTarget.BIOMETRIC, SettingsSearchTarget.BIOMETRIC_TIMEOUT,
            SettingsSearchTarget.FIDO2, SettingsSearchTarget.YUBIKEY,
        )
        assertFalse(ios.any { it.target in hardwareTargets })
        assertTrue(ios.any { it.target == SettingsSearchTarget.EXTERNAL_BROWSER })
        assertFalse(ios.any { it.target == SettingsSearchTarget.MINIMIZE })

        val mac = catalog(macOS = true, biometric = true, fido2 = true, yubiKey = true)
        assertTrue(mac.any { it.target == SettingsSearchTarget.LAUNCH_AT_LOGIN })
        assertTrue(mac.any { it.target == SettingsSearchTarget.MINIMIZE })
        assertTrue(mac.any { it.target == SettingsSearchTarget.GPG_SCOPE })
        assertFalse(mac.any { it.target == SettingsSearchTarget.KEEP_AWAKE })
        val allTargets = (ios + mac).mapNotNull { it.target }.toSet()
        assertEquals(SettingsSearchTarget.entries.toSet(), allTargets)
        assertEquals(mac.size, mac.map { it.id }.distinct().size)
    }

    @Test
    fun respectsTheVisibleCategoryCatalogIncludingReleaseGates() = runTest {
        val entries = SettingsSearchCatalog.entries(
            categories = listOf(category("security"), category("notifications")),
            capabilities = SettingsSearchCapabilities(false, false, false, false),
            biometricTitle = "Biometrics",
            text = { _, _ -> "Setting" },
        )
        assertTrue(entries.all { it.categoryId == "security" })
        assertFalse(entries.any { it.target == SettingsSearchTarget.DEBUG_PREMIUM })
    }

    @Test
    fun includesAuditedReadOnlyRowsAndConfigurationActions() = runTest {
        // Audited independently against native pages. Footnotes and transient errors
        // belong to their parent row; dynamic products, user data and token values
        // are deliberately not separate search results.
        val expected = mapOf(
            "about" to setOf("APP_VERSION", "BUILD_DATE", "BUILD_REF", "CHANGELOG"),
            "automatic_backups" to setOf("BACKUP_STATUS", "BACKUP_LAST_SUCCESS", "BACKUP_LOCATION", "BACKUP_PASSWORD"),
            "autofill" to setOf("AUTOFILL_STATUS", "AUTOFILL_INDEX_STATUS"),
            "developer" to setOf("SSH_STATUS", "SSH_SOCKET", "GPG_STATUS"),
            "subscription" to setOf(
                "MEMBERSHIP_STATUS", "RESTORE_PURCHASES", "MANAGE_PURCHASES", "PURCHASE_TERMS",
                "LICENSE_ENTRY", "LICENSE_SYNC", "LICENSE_PURCHASE_TOKEN", "LICENSE_LINKED_TOKEN",
                "LICENSE_LINK", "LICENSE_REFRESH", "LICENSE_REMOVE",
            ),
        )
        val entries = catalog(macOS = true)
        expected.forEach { (category, ids) ->
            assertTrue(entries.filter { it.categoryId == category }.map { it.id }.containsAll(ids), category)
        }
    }

    @Test
    fun filtersAbsentMetadataAndStoreActionsWithoutHidingSupportedReadOnlyRows() = runTest {
        val entries = catalog(macOS = true, store = false, appInformation = AppInformationSnapshot(loaded = true))
        val absent = setOf(
            SettingsSearchTarget.BUILD_DATE, SettingsSearchTarget.BUILD_REF, SettingsSearchTarget.CHANGELOG,
            SettingsSearchTarget.RESTORE_PURCHASES, SettingsSearchTarget.MANAGE_PURCHASES,
            SettingsSearchTarget.PURCHASE_TERMS, SettingsSearchTarget.LICENSE_SYNC,
        )
        assertFalse(entries.any { it.target in absent })
        assertTrue(entries.any { it.target == SettingsSearchTarget.APP_VERSION })
        assertTrue(entries.any { it.target == SettingsSearchTarget.MEMBERSHIP_STATUS })
        assertTrue(entries.any { it.target == SettingsSearchTarget.LICENSE_LINKED_TOKEN })
    }

    @Test
    fun readOnlyLabelsAndSynonymsAreSearchableButRuntimeValuesAreNot() = runTest {
        val translations = mapOf(
            Res.string.pref_item_app_version_title to "App version",
            Res.string.pref_item_automatic_backups_panel_last_sync_title to "Last backup",
            Res.string.settingssearch_backup_location_keywords to "destination folder server WebDAV S3 bucket",
            Res.string.settingssearch_backup_password_keywords to "encryption password configured",
            Res.string.settingssearch_ssh_socket_keywords to "SSH_AUTH_SOCK socket path environment client",
            Res.string.settingssearch_build_ref_keywords to "revision commit source build reference",
            Res.string.pref_item_autofill_default_match_detection_title to "Default match detection",
            Res.string.uri to "URI",
            Res.string.url to "URL",
        )
        val metadata = AppInformationSnapshot(
            loaded = true, buildDate = "private-date", buildRef = "private-revision", buildRefUrl = "private-url",
        )
        val index = SettingsSearchIndex(
            catalog(macOS = true, appInformation = metadata) { translations[it].orEmpty() }, "en",
        )
        val queries = mapOf(
            "app version" to SettingsSearchTarget.APP_VERSION,
            "last backup" to SettingsSearchTarget.BACKUP_LAST_SUCCESS,
            "destination webdav" to SettingsSearchTarget.BACKUP_LOCATION,
            "s3 bucket" to SettingsSearchTarget.BACKUP_LOCATION,
            "encryption configured" to SettingsSearchTarget.BACKUP_PASSWORD,
            "SSH_AUTH_SOCK" to SettingsSearchTarget.SSH_SOCKET,
            "revision commit" to SettingsSearchTarget.BUILD_REF,
            "URI match" to SettingsSearchTarget.AUTOFILL_DEFAULT_MATCH_DETECTION,
            "URL match" to SettingsSearchTarget.AUTOFILL_DEFAULT_MATCH_DETECTION,
        )
        queries.forEach { (query, target) -> assertEquals(target, index.search(query).single().target, query) }
        listOf("private-date", "private-revision", "private-url").forEach { assertTrue(index.search(it).isEmpty()) }
    }

    private suspend fun catalog(
        macOS: Boolean,
        biometric: Boolean = false,
        fido2: Boolean = false,
        yubiKey: Boolean = false,
        store: Boolean = true,
        appInformation: AppInformationSnapshot = AppInformationSnapshot(
            loaded = true, buildDate = "date", buildRefUrl = "ref", changelogUrl = "changes",
        ),
        text: suspend (org.jetbrains.compose.resources.StringResource) -> String = { "Setting" },
    ) = SettingsSearchCatalog.entries(
        categories = listOf(
            "security", "display", "autofill", "automatic_backups", "watchtower", "debug", "about", "subscription",
        )
            .let { if (macOS) it + "developer" else it }
            .map(::category),
        capabilities = SettingsSearchCapabilities(macOS, biometric, fido2, yubiKey, store, appInformation),
        biometricTitle = "Face ID",
        text = { res, _ -> text(res) },
    )

    private fun category(id: String) = SettingsItemSnapshot(id, SettingsItemKind.ACTION, id, null)

    private fun entry(
        id: String,
        title: String,
        path: String = "",
        description: String = "",
        keywords: String = "",
    ) = SettingsSearchEntrySnapshot(id, title, "security", null, path, description, keywords)
}
