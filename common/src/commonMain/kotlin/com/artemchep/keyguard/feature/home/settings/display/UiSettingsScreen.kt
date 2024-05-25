package com.artemchep.keyguard.feature.home.settings.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun UiSettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "locale",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.LOCALE),
                ),
            ),
            SettingPaneItem.Group(
                key = "color_scheme",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.COLOR_SCHEME),
                    SettingPaneItem.Item(Setting.COLOR_SCHEME_AMOLED_DARK),
                    SettingPaneItem.Item(Setting.COLOR_ACCENT),
                    SettingPaneItem.Item(Setting.FONT),
                    SettingPaneItem.Item(Setting.MARKDOWN),
                    SettingPaneItem.Item(Setting.NAV_ANIMATION),
                    SettingPaneItem.Item(Setting.NAV_LABEL),
                ),
            ),
            SettingPaneItem.Group(
                key = "icons",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.APP_ICONS),
                    SettingPaneItem.Item(Setting.WEBSITE_ICONS),
                    SettingPaneItem.Item(Setting.GRAVATAR),
                ),
            ),
            SettingPaneItem.Group(
                key = "experience",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.USE_EXTERNAL_BROWSER),
                    SettingPaneItem.Item(Setting.KEEP_SCREEN_ON),
                    SettingPaneItem.Item(Setting.CLOSE_TO_TRAY),
                ),
            ),
            SettingPaneItem.Group(
                key = "layout",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.TWO_PANEL_LAYOUT_PORTRAIT),
                    SettingPaneItem.Item(Setting.TWO_PANEL_LAYOUT_LANDSCAPE),
                ),
            ),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_appearance_header_title),
        items = items,
    )
}
