package com.artemchep.keyguard.feature.home.settings.developer

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
fun DeveloperSettingsScreen() {
    val items = rememberSettingsDeveloperItems()
    SettingPaneContent(
        title = stringResource(Res.string.settings_developer_header_title),
        items = items,
    )
}

@Composable
fun rememberSettingsDeveloperItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "agent",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SSH_SETTINGS),
                    SettingPaneItem.Item(Setting.GPG_SETTINGS),
                ),
            ),
            SettingPaneItem.Group(
                key = "connected_apps",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CONNECTED_APPS),
                ),
            ),
        )
    }
}
