package com.artemchep.keyguard.feature.home.settings.watchtower

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun WatchtowerSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_watchtower_header_title),
        items = listOf(
            SettingPaneItem.Item("check_pwned_services"),
            SettingPaneItem.Item("check_pwned_passwords"),
            SettingPaneItem.Item("check_two_fa"),
        ),
    )
}
