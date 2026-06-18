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
                key = "ssh_agent.control_panel",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SSH_AGENT),
                    SettingPaneItem.Item(Setting.SSH_AGENT_SETUP),
                ),
            ),
            SettingPaneItem.Group(
                key = "ssh_agent.help",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SSH_AGENT_LOCAL_STORAGE_INFO),
                ),
            ),
            SettingPaneItem.Group(
                key = "ssh_agent.settings",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SSH_AGENT_APPROVAL_WINDOW),
                    SettingPaneItem.Item(Setting.SSH_AGENT_DISPLAY_KEY_NAMES),
                    SettingPaneItem.Item(Setting.SSH_AGENT_FILTERS),
                ),
            ),
            SettingPaneItem.Group(
                key = "ssh_agent.history",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SSH_AGENT_HISTORY),
                ),
            ),
        )
    }
}
