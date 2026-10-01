package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.Fido2UnlockAvailability
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.feature.datasafety.DataSafetyItem
import com.artemchep.keyguard.feature.datasafety.dataSafetyCatalog
import com.artemchep.keyguard.feature.home.settings.SettingsCatalogItem
import com.artemchep.keyguard.feature.home.settings.settingsCatalog
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.team.AboutTeamCatalog
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.learn_more
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The shared static catalogs; no unlocked vault needed. */
internal class StaticDataController(
    private val ctx: CoreContext,
) {
    suspend fun loadSettingsSearch(
        categories: List<SettingsItemSnapshot>,
        biometricTitle: String,
        localeIdentifier: String,
    ): SettingsSearchIndex = withContext(Dispatchers.Default) {
        val context = ctx.koin.get<LeContext>()
        val capabilities = SettingsSearchCapabilities(
            macOS = CurrentPlatform is Platform.Desktop,
            biometric = ctx.koin.get<BiometricStatusUseCase>()().first() is BiometricStatus.Available,
            fido2 = ctx.koin.get<Fido2UnlockAvailability>().isSupported(),
            yubiKey = ctx.koin.get<YubiKeyUnlockAvailability>().isSupported(),
            store = !ctx.koin.get<FlavorConfig>().isFreeAsBeer,
            appInformation = AppInformationController(ctx).loadAppInformation(),
        )
        SettingsSearchIndex(
            entries = SettingsSearchCatalog.entries(categories, capabilities, biometricTitle) {
                textResource(it, context)
            },
            localeIdentifier = localeIdentifier,
        )
    }

    suspend fun loadAboutTeam(): AboutTeamSnapshot = withContext(Dispatchers.Default) {
        val leContext = ctx.koin.get<LeContext>()
        AboutTeamSnapshot(
            name = AboutTeamCatalog.NAME,
            flag = AboutTeamCatalog.FLAG,
            about = textResource(AboutTeamCatalog.about, leContext),
            socialNetworks = AboutTeamCatalog.socialNetworks.map { link ->
                AboutTeamSocialSnapshot(title = link.title, username = link.username, url = link.url)
            },
        )
    }

    suspend fun loadDataSafety(): List<DataSafetyItemSnapshot> = withContext(Dispatchers.Default) {
        val leContext = ctx.koin.get<LeContext>()
        dataSafetyCatalog(
            text = { textResource(it, leContext) },
            format = { resource, argument -> textResource(resource, leContext, argument) },
        ).mapNotNull { item ->
            when (item) {
                is DataSafetyItem.LargeSection -> dataSafetySnapshot(
                    item.key, DataSafetyItemKind.LARGE_SECTION, text = item.text,
                )
                is DataSafetyItem.Section -> dataSafetySnapshot(
                    item.key, DataSafetyItemKind.SECTION, text = item.text,
                )
                is DataSafetyItem.Text -> dataSafetySnapshot(
                    item.key, DataSafetyItemKind.TEXT, text = item.text, secondary = item.secondary,
                )
                is DataSafetyItem.Row -> dataSafetySnapshot(
                    item.key, DataSafetyItemKind.ROW,
                    title = item.title, value = item.value, secondary = item.secondary,
                )
                is DataSafetyItem.LearnMore -> dataSafetySnapshot(
                    item.key, DataSafetyItemKind.LEARN_MORE,
                    text = textResource(Res.string.learn_more, leContext), url = item.url,
                )
                // SwiftUI supplies native spacing and separators.
                is DataSafetyItem.Spacer, is DataSafetyItem.Divider -> null
            }
        }
    }

    suspend fun loadSettingsList(): SettingsListSnapshot = withContext(Dispatchers.Default) {
        val leContext = ctx.koin.get<LeContext>()
        val items = settingsCatalog(
            config = ctx.koin.get<FlavorConfig>(),
            includeDeveloper = CurrentPlatform is Platform.Desktop,
            // Both native apps host the backup setup wizard.
            includeAutomaticBackups = true,
        ).map { item ->
            SettingsItemSnapshot(
                id = item.id,
                kind = when (item) {
                    is SettingsCatalogItem.Section -> SettingsItemKind.SECTION
                    is SettingsCatalogItem.Action -> SettingsItemKind.ACTION
                },
                title = textResource(item.title, leContext),
                text = (item as? SettingsCatalogItem.Action)?.let { textResource(it.text, leContext) },
            )
        }
        SettingsListSnapshot(items = items)
    }
}

private fun dataSafetySnapshot(
    id: String,
    kind: DataSafetyItemKind,
    text: String = "",
    title: String = "",
    value: String = "",
    secondary: Boolean = false,
    url: String? = null,
) = DataSafetyItemSnapshot(id, kind, text, title, value, secondary, url)
