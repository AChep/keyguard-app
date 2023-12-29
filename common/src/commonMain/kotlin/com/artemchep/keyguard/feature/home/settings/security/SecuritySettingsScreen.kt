package com.artemchep.keyguard.feature.home.settings.security

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun SecuritySettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_security_header_title),
        items = listOf(
            SettingPaneItem.Item(Setting.VAULT_PERSIST),
            SettingPaneItem.Item(Setting.VAULT_LOCK_AFTER_TIMEOUT),
            SettingPaneItem.Item(Setting.VAULT_LOCK_AFTER_SCREEN_OFF),
            SettingPaneItem.Item(Setting.VAULT_LOCK_AFTER_REBOOT),
            SettingPaneItem.Item(Setting.VAULT_LOCK),
            SettingPaneItem.Group(
                key = "clipboard",
                list = listOf(
                    SettingPaneItem.Item(Setting.CLIPBOARD_AUTO_REFRESH),
                ),
            ),
            SettingPaneItem.Group(
                key = "visuals",
                list = listOf(
                    SettingPaneItem.Item(Setting.CONCEAL),
                    SettingPaneItem.Item(Setting.SCREENSHOTS),
                    SettingPaneItem.Item(Setting.WEBSITE_ICONS),
                ),
            ),
            SettingPaneItem.Group(
                key = "biometric",
                list = listOf(
                    SettingPaneItem.Item(Setting.BIOMETRIC),
                    SettingPaneItem.Item(Setting.REQUIRE_MASTER_PASSWORD),
                ),
            ),
            SettingPaneItem.Group(
                key = "password",
                list = listOf(
                    SettingPaneItem.Item(Setting.MASTER_PASSWORD),
                ),
            ),
        ),
    )
}
