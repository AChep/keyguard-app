package com.artemchep.keyguard.feature.home.settings.other

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
fun OtherSettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "features",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.FEATURES_OVERVIEW),
                ),
            ),
            SettingPaneItem.Group(
                key = "other",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.URL_OVERRIDE),
                ),
            ),
            SettingPaneItem.Group(
                key = "security",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CRASHLYTICS),
                    SettingPaneItem.Item(Setting.DATA_SAFETY),
                    SettingPaneItem.Item(Setting.PERMISSION_DETAILS),
                ),
            ),
            SettingPaneItem.Group(
                key = "social",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.RATE_APP),
                    SettingPaneItem.Item(Setting.ABOUT_TEAM),
                    SettingPaneItem.Item(Setting.REDDIT),
                    SettingPaneItem.Item(Setting.GITHUB),
                    SettingPaneItem.Item(Setting.CROWDIN),
                    SettingPaneItem.Item(Setting.FEEDBACK_APP),
                    SettingPaneItem.Item(Setting.OPEN_SOURCE_LICENSES),
                    SettingPaneItem.Item(Setting.PRIVACY_POLICY),
                ),
            ),
            SettingPaneItem.Item(Setting.ABOUT_APP),
            SettingPaneItem.Item(Setting.ABOUT_APP_BUILD_DATE),
            SettingPaneItem.Item(Setting.ABOUT_APP_BUILD_REF),
            SettingPaneItem.Item(Setting.ABOUT_APP_CHANGELOG),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_other_header_title),
        items = items,
    )
}
