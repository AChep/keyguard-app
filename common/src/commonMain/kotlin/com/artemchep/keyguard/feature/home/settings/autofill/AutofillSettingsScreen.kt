package com.artemchep.keyguard.feature.home.settings.autofill

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
fun AutofillSettingsScreen() {
    val items = remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "credential",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.CREDENTIAL_PROVIDER),
                ),
            ),
            SettingPaneItem.Group(
                key = "autofill",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.AUTOFILL),
                ),
            ),
            SettingPaneItem.Group(
                key = "general",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.AUTOFILL_INLINE_SUGGESTIONS),
                    SettingPaneItem.Item(Setting.AUTOFILL_MANUAL_SELECTION),
                    SettingPaneItem.Item(Setting.AUTOFILL_RESPECT_AUTOFILL_OFF),
                ),
            ),
            SettingPaneItem.Group(
                key = "totp",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.AUTOFILL_COPY_TOTP),
                ),
            ),
            SettingPaneItem.Group(
                key = "save",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.AUTOFILL_SAVE_REQUEST),
                    SettingPaneItem.Item(Setting.AUTOFILL_SAVE_URI),
                ),
            ),
        )
    }
    SettingPaneContent(
        title = stringResource(Res.string.settings_autofill_header_title),
        items = items,
    )
}
