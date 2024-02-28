package com.artemchep.keyguard.feature.home.settings.subscriptions

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun SubscriptionsSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_subscriptions_header_title),
        items = listOf(
            SettingPaneItem.Item(Setting.SUBSCRIPTIONS),
            SettingPaneItem.Group(
                key = "other",
                title = stringResource(Res.strings.misc),
                list = listOf(
                    SettingPaneItem.Item(Setting.SUBSCRIPTIONS_IN_STORE),
                    SettingPaneItem.Item(Setting.ABOUT_TEAM),
                    SettingPaneItem.Item(Setting.FEEDBACK_APP),
                    SettingPaneItem.Item(Setting.APK),
                ),
            ),
        ),
    )
}
