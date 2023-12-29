package com.artemchep.keyguard.feature.home.settings.permissions

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun PermissionsSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_permissions_header_title),
        items = listOf(
            SettingPaneItem.Group(
                key = "runtime",
                list = listOf(
                    SettingPaneItem.Item(Setting.PERMISSION_CAMERA),
                    SettingPaneItem.Item(Setting.PERMISSION_POST_NOTIFICATION),
                ),
            ),
            SettingPaneItem.Group(
                key = "other",
                list = listOf(
                    SettingPaneItem.Item(Setting.PERMISSION_OTHER),
                ),
            ),
        ),
    )
}
