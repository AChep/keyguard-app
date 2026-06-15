package com.artemchep.keyguard.wear.feature.settings.ui

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.display.rememberSettingsUiItems
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_appearance_header_title
import com.artemchep.keyguard.wear.feature.settings.WearSettingsPaneScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearSettingsUiScreen() {
    val items = rememberSettingsUiItems()
    WearSettingsPaneScaffold(
        title = stringResource(Res.string.settings_appearance_header_title),
        items = items,
    )
}
