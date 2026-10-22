package com.artemchep.keyguard.feature.home.settings.environments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_title
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun EnvironmentsSettingsScreen() {
    val items = rememberSettingsEnvironmentsItems()
    SettingPaneContent(
        title = stringResource(Res.string.environments_title),
        items = items,
    )
}

@Composable
fun rememberSettingsEnvironmentsItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "environments.items",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_LIST),
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_CREATE),
                ),
            ),
            SettingPaneItem.Group(
                key = "environments.delivery",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_MOUNTS),
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_MCP_SETTINGS),
                ),
            ),
        )
    }
}
