package com.artemchep.keyguard.feature.home.settings.watchtower

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun WatchtowerSettingsScreen() {
    val items = rememberSettingsWatchtowerItems()
    SettingPaneContent(
        title = stringResource(Res.string.settings_watchtower_header_title),
        items = items,
    )
}

@Composable
fun rememberSettingsWatchtowerItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "hibp",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CHECK_PWNED_PASSWORDS),
                    SettingPaneItem.Item(Setting.CHECK_PWNED_SERVICES),
                    SettingPaneItem.Item(Setting.HIBP_API_TOKEN),
                ),
            ),
            SettingPaneItem.Group(
                key = "2fa",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CHECK_TWO_FA),
                ),
            ),
            SettingPaneItem.Group(
                key = "passkeys",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CHECK_PASSKEYS),
                ),
            ),
        )
    }
}
