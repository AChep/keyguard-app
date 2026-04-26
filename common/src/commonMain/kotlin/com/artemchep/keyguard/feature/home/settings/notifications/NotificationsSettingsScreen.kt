package com.artemchep.keyguard.feature.home.settings.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun NotificationsSettingsScreen() {
    val items = rememberSettingsNotificationItems()
    SettingPaneContent(
        title = stringResource(Res.string.settings_notifications_header_title),
        items = items,
    )
}

@Composable
fun rememberSettingsNotificationItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Item(Setting.PERMISSION_CAMERA),
            SettingPaneItem.Item(Setting.PERMISSION_POST_NOTIFICATION),
        )
    }
}
