package com.artemchep.keyguard.feature.home.settings.permissions

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
fun PermissionsSettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "runtime",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.PERMISSION_CAMERA),
                    SettingPaneItem.Item(Setting.PERMISSION_POST_NOTIFICATION),
                    SettingPaneItem.Item(Setting.PERMISSION_WRITE_EXTERNAL_STORAGE),
                ),
            ),
            SettingPaneItem.Group(
                key = "other",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.PERMISSION_OTHER),
                ),
            ),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_permissions_header_title),
        items = items,
    )
}
