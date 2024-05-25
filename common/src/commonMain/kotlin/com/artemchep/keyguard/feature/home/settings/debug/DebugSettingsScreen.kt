package com.artemchep.keyguard.feature.home.settings.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun DebugSettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "ui",
                title = "UI".let(TextHolder::Value),
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.EMIT_MESSAGE),
                    SettingPaneItem.Item(Setting.EMIT_TOTP),
                    SettingPaneItem.Item(Setting.CLEAR_CACHE),
                    SettingPaneItem.Item(Setting.LAUNCH_APP_PICKER),
                    SettingPaneItem.Item(Setting.LAUNCH_YUBIKEY),
                    SettingPaneItem.Item(Setting.CRASH),
                    SettingPaneItem.Item(Setting.SCREEN_DELAY),
                ),
            ),
            SettingPaneItem.Group(
                key = "billing",
                title = "Billing".let(TextHolder::Value),
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.SUBSCRIPTIONS_DEBUG),
                ),
            ),
            SettingPaneItem.Group(
                key = "security",
                title = "Security".let(TextHolder::Value),
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.ROTATE_DEVICE_ID),
                ),
            ),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_dev_header_title),
        items = items,
    )
}
