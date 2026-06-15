package com.artemchep.keyguard.wear.feature.settings.autofill

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.autofill.rememberSettingsAutofillItems
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_autofill_header_title
import com.artemchep.keyguard.wear.feature.settings.WearSettingsPaneScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearSettingsAutofillScreen() {
    val items = rememberSettingsAutofillItems()
    WearSettingsPaneScaffold(
        title = stringResource(Res.string.settings_autofill_header_title),
        items = items,
    )
}
