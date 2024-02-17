package com.artemchep.keyguard.feature.home.settings.display

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun UiSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_appearance_header_title),
        items = listOf(
            SettingPaneItem.Group(
                key = "locale",
                list = listOf(
                    SettingPaneItem.Item(Setting.LOCALE),
                ),
            ),
            SettingPaneItem.Group(
                key = "color_scheme",
                list = listOf(
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
                list = listOf(
                    SettingPaneItem.Item(Setting.APP_ICONS),
                    SettingPaneItem.Item(Setting.WEBSITE_ICONS),
                ),
            ),
            SettingPaneItem.Group(
                key = "experience",
                list = listOf(
                    SettingPaneItem.Item(Setting.USE_EXTERNAL_BROWSER),
                    SettingPaneItem.Item(Setting.KEEP_SCREEN_ON),
                    SettingPaneItem.Item(Setting.CLOSE_TO_TRAY),
                ),
            ),
            SettingPaneItem.Group(
                key = "layout",
                list = listOf(
                    SettingPaneItem.Item(Setting.TWO_PANEL_LAYOUT_PORTRAIT),
                    SettingPaneItem.Item(Setting.TWO_PANEL_LAYOUT_LANDSCAPE),
                ),
            ),
        ),
    )
}
