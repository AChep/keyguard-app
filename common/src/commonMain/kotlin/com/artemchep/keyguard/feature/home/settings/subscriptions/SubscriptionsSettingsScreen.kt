package com.artemchep.keyguard.feature.home.settings.subscriptions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun SubscriptionsSettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Item(Setting.SUBSCRIPTIONS),
            SettingPaneItem.Group(
                key = "other",
                title = Res.string.misc.wrap(),
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SUBSCRIPTIONS_IN_STORE),
                    SettingPaneItem.Item(Setting.ABOUT_TEAM),
                    SettingPaneItem.Item(Setting.FEEDBACK_APP),
                    SettingPaneItem.Item(Setting.APK),
                ),
            ),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_subscriptions_header_title),
        items = items,
    )
}
