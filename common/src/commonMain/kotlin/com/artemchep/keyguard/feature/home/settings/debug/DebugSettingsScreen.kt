package com.artemchep.keyguard.feature.home.settings.debug

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun DebugSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_dev_header_title),
        items = listOf(
            SettingPaneItem.Group(
                key = "ui",
                title = "UI",
                list = listOf(
                    SettingPaneItem.Item(Setting.EMIT_MESSAGE),
                    SettingPaneItem.Item(Setting.CLEAR_CACHE),
                    SettingPaneItem.Item(Setting.LAUNCH_APP_PICKER),
                    SettingPaneItem.Item(Setting.LAUNCH_YUBIKEY),
                    SettingPaneItem.Item(Setting.CRASH),
                    SettingPaneItem.Item(Setting.SCREEN_DELAY),
                ),
            ),
            SettingPaneItem.Group(
                key = "billing",
                title = "Billing",
                list = listOf(
                    SettingPaneItem.Item(Setting.SUBSCRIPTIONS_DEBUG),
                ),
            ),
            SettingPaneItem.Group(
                key = "security",
                title = "Security",
                list = listOf(
                    SettingPaneItem.Item(Setting.ROTATE_DEVICE_ID),
                ),
            ),
        ),
    )
}
