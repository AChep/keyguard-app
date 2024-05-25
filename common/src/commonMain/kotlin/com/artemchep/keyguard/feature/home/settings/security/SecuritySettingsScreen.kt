package com.artemchep.keyguard.feature.home.settings.security

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
fun SecuritySettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Item(Setting.VAULT_PERSIST),
            SettingPaneItem.Item(Setting.VAULT_LOCK_AFTER_TIMEOUT),
            SettingPaneItem.Item(Setting.VAULT_LOCK_AFTER_SCREEN_OFF),
            SettingPaneItem.Item(Setting.VAULT_LOCK_AFTER_REBOOT),
            SettingPaneItem.Item(Setting.VAULT_LOCK),
            SettingPaneItem.Group(
                key = "clipboard",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CLIPBOARD_AUTO_REFRESH),
                    SettingPaneItem.Item(Setting.CLIPBOARD_NOTIFICATION_SETTINGS),
                ),
            ),
            SettingPaneItem.Group(
                key = "visuals",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CONCEAL),
                    SettingPaneItem.Item(Setting.SCREENSHOTS),
                    SettingPaneItem.Item(Setting.WEBSITE_ICONS),
                    SettingPaneItem.Item(Setting.GRAVATAR),
                ),
            ),
            SettingPaneItem.Group(
                key = "biometric",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.BIOMETRIC),
                    SettingPaneItem.Item(Setting.BIOMETRIC_REQUIRE_CONFIRMATION),
                    SettingPaneItem.Item(Setting.REQUIRE_MASTER_PASSWORD),
                ),
            ),
            SettingPaneItem.Group(
                key = "password",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.MASTER_PASSWORD),
                ),
            ),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_security_header_title),
        items = items,
    )
}
