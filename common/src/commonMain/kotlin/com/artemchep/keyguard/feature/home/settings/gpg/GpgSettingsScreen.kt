package com.artemchep.keyguard.feature.home.settings.gpg

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
fun GpgSettingsScreen() {
    val items = rememberSettingsGpgItems()
    SettingPaneContent(
        title = stringResource(Res.string.settings_gpg_agent_header_title),
        items = items,
    )
}

@Composable
fun rememberSettingsGpgItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "gpg_agent.control_panel",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.GPG_AGENT),
                    SettingPaneItem.Item(Setting.GPG_AGENT_SETUP),
                    SettingPaneItem.Item(Setting.GPG_KEYSERVER_SEARCH),
                ),
            ),
            SettingPaneItem.Group(
                key = "gpg_agent.help",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.GPG_AGENT_LOCAL_STORAGE_INFO),
                ),
            ),
            SettingPaneItem.Group(
                key = "gpg_agent.settings",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.GPG_AGENT_APPROVAL_WINDOW),
                    SettingPaneItem.Item(Setting.GPG_AGENT_APPROVAL_CACHE_POLICY),
                    SettingPaneItem.Item(Setting.GPG_AGENT_DISPLAY_KEY_NAMES),
                    SettingPaneItem.Item(Setting.GPG_AGENT_FILTERS),
                ),
            ),
            SettingPaneItem.Group(
                key = "gpg_agent.keyserver",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.GPG_KEYSERVER_URL),
                    SettingPaneItem.Item(Setting.GPG_KEYSERVER_PROTOCOL),
                    SettingPaneItem.Item(Setting.GPG_KEYSERVER_AUTO_REFRESH),
                    SettingPaneItem.Item(Setting.GPG_KEYSERVER_REFRESH_INTERVAL),
                ),
            ),
            SettingPaneItem.Group(
                key = "gpg_agent.history",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.GPG_AGENT_HISTORY),
                ),
            ),
        )
    }
}
