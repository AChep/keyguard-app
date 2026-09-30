package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.feature.home.settings.SettingsDestination
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_general_header_title
import com.artemchep.keyguard.res.unlock_biometric_title
import org.jetbrains.compose.resources.StringResource

/** The macOS-only General page; mirrors `SettingsView.generalTag` in Swift. */
internal const val GENERAL_CATEGORY_ID = "macos_general"

internal data class SettingsSearchCapabilities(
    val macOS: Boolean,
    val biometric: Boolean,
    val fido2: Boolean,
    val yubiKey: Boolean,
    val store: Boolean = false,
    val appInformation: AppInformationSnapshot = AppInformationSnapshot.empty,
)

/** Metadata only: no Compose content, preference values, accounts, or vault data. */
internal object SettingsSearchCatalog {
    suspend fun entries(
        categories: List<SettingsItemSnapshot>,
        capabilities: SettingsSearchCapabilities,
        biometricTitle: String,
        text: suspend (StringResource) -> String,
    ): List<SettingsSearchEntrySnapshot> {
        // The native biometric label (Touch ID / Face ID) replaces the generic one.
        val resolve: suspend (StringResource) -> String = {
            if (it == Res.string.unlock_biometric_title) biometricTitle else text(it)
        }
        // Notifications is still a placeholder in the native apps, even when the
        // development catalog exposes it. Search only offers usable destinations.
        val availableCategories = categories.filter {
            it.kind == SettingsItemKind.ACTION && it.id != SettingsDestination.NOTIFICATIONS.id
        }.toMutableList()
        if (capabilities.macOS) {
            availableCategories.add(
                0,
                SettingsItemSnapshot(
                    id = GENERAL_CATEGORY_ID,
                    kind = SettingsItemKind.ACTION,
                    title = resolve(Res.string.settings_general_header_title),
                    text = null,
                ),
            )
        }
        val categoriesById = availableCategories.associateBy { it.id }
        val categoryEntries = availableCategories.map { category ->
            SettingsSearchEntrySnapshot(
                category.id, category.title, category.id, null, null, category.text.orEmpty(), "",
            )
        }
        val controls = SettingsSearchTarget.entries.mapNotNull { target ->
            val category = categoriesById[target.categoryId] ?: return@mapNotNull null
            if (!target.availability.supported(capabilities)) return@mapNotNull null
            SettingsSearchEntrySnapshot(
                id = target.name,
                title = resolve(target.title),
                categoryId = category.id,
                target = target,
                path = listOfNotNull(category.title, target.section?.let { resolve(it) }).joinToString(" › "),
                description = target.description?.let { resolve(it) }.orEmpty(),
                keywords = target.keywords.map { resolve(it) }.joinToString(" "),
            )
        }
        return categoryEntries + controls
    }
}
