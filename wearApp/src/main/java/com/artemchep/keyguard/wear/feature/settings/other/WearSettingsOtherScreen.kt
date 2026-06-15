package com.artemchep.keyguard.wear.feature.settings.other

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.other.rememberSettingsOtherItems
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_other_header_title
import com.artemchep.keyguard.wear.feature.settings.WearSettingsPaneScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearSettingsOtherScreen() {
    val items = rememberSettingsOtherItems()
    WearSettingsPaneScaffold(
        title = stringResource(Res.string.settings_other_header_title),
        items = items,
    )
}
