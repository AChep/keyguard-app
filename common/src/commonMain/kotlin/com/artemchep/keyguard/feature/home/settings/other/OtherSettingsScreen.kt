package com.artemchep.keyguard.feature.home.settings.other

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun OtherSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_other_header_title),
        items = listOf(
            SettingPaneItem.Group(
                key = "features",
                list = listOf(
                    SettingPaneItem.Item(Setting.FEATURES_OVERVIEW),
                ),
            ),
            SettingPaneItem.Group(
                key = "other",
                list = listOf(
                    SettingPaneItem.Item(Setting.URL_OVERRIDE),
                ),
            ),
            SettingPaneItem.Group(
                key = "security",
                list = listOf(
                    SettingPaneItem.Item(Setting.CRASHLYTICS),
                    SettingPaneItem.Item(Setting.DATA_SAFETY),
                    SettingPaneItem.Item(Setting.PERMISSION_DETAILS),
                ),
            ),
//            SettingPaneItem.Group(
//                key = "exp",
//                list = listOf(
//                    SettingPaneItem.Item(Setting.EXPERIMENTAL),
//                ),
//            ),
            SettingPaneItem.Group(
                key = "social",
                list = listOf(
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
        ),
    )
}
