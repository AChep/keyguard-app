package com.artemchep.keyguard.feature.home.settings.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cli_title
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun CliSettingsScreen() {
    val items = rememberSettingsCliItems()
    SettingPaneContent(
        title = stringResource(Res.string.cli_title),
        items = items,
    )
}

@Composable
fun rememberSettingsCliItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "cli.control_panel",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CLI),
                    SettingPaneItem.Item(Setting.CLI_APPROVAL_WINDOW),
                ),
            ),
            SettingPaneItem.Group(
                key = "cli.setup",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CLI_SETUP),
                ),
            ),
        )
    }
}
