package com.artemchep.keyguard.feature.home.settings.autofill

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun AutofillSettingsScreen() {
    SettingPaneContent(
        title = stringResource(Res.strings.settings_autofill_header_title),
        items = listOf(
            SettingPaneItem.Group(
                key = "credential",
                list = listOf(
                    SettingPaneItem.Item(Setting.CREDENTIAL_PROVIDER),
                ),
            ),
            SettingPaneItem.Group(
                key = "autofill",
                list = listOf(
                    SettingPaneItem.Item(Setting.AUTOFILL),
                ),
            ),
            SettingPaneItem.Group(
                key = "general",
                list = listOf(
                    SettingPaneItem.Item(Setting.AUTOFILL_INLINE_SUGGESTIONS),
                    SettingPaneItem.Item(Setting.AUTOFILL_MANUAL_SELECTION),
                    SettingPaneItem.Item(Setting.AUTOFILL_RESPECT_AUTOFILL_OFF),
                ),
            ),
            SettingPaneItem.Group(
                key = "totp",
                list = listOf(
                    SettingPaneItem.Item(Setting.AUTOFILL_COPY_TOTP),
                ),
            ),
            SettingPaneItem.Group(
                key = "save",
                list = listOf(
                    SettingPaneItem.Item(Setting.AUTOFILL_SAVE_REQUEST),
                    SettingPaneItem.Item(Setting.AUTOFILL_SAVE_URI),
                ),
            ),
        ),
    )
}
