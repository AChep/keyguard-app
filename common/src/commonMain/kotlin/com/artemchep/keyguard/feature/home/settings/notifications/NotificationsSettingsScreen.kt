package com.artemchep.keyguard.feature.home.settings.notifications

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun NotificationsSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_notifications_header_title),
        items = listOf(
            SettingPaneItem.Item(Setting.PERMISSION_CAMERA),
            SettingPaneItem.Item(Setting.PERMISSION_POST_NOTIFICATION),
        ),
    )
}
