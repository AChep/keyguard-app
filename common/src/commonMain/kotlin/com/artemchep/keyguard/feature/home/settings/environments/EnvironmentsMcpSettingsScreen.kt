package com.artemchep.keyguard.feature.home.settings.environments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.protocol_mcp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun EnvironmentsMcpSettingsScreen() {
    val items = rememberSettingsEnvironmentsMcpItems()
    SettingPaneContent(
        title = stringResource(Res.string.protocol_mcp),
        items = items,
    )
}

@Composable
fun rememberSettingsEnvironmentsMcpItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "environments_mcp.control_panel",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_MCP),
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_MCP_APPROVAL_WINDOW),
                ),
            ),
            SettingPaneItem.Group(
                key = "environments_mcp.setup",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.ENVIRONMENTS_MCP_SETUP),
                ),
            ),
        )
    }
}
