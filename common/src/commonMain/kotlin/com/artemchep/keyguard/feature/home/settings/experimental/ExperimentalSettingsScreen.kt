package com.artemchep.keyguard.feature.home.settings.experimental

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun ExperimentalSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_experimental_header_title),
        items = listOf(
            SettingPaneItem.Item(Setting.WRITE_ACCESS),
        ),
    )
}
