package com.artemchep.keyguard.feature.home.settings.watchtower

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun WatchtowerSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_watchtower_header_title),
        items = listOf(
            SettingPaneItem.Item(Setting.CHECK_PWNED_PASSWORDS),
            SettingPaneItem.Item(Setting.CHECK_PWNED_SERVICES),
            SettingPaneItem.Item(Setting.CHECK_TWO_FA),
            SettingPaneItem.Item(Setting.CHECK_PASSKEYS),
        ),
    )
}
