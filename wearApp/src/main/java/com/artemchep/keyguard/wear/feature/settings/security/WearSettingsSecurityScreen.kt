package com.artemchep.keyguard.wear.feature.settings.security

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.settings.security.rememberSettingsSecurityItems
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_security_header_title
import com.artemchep.keyguard.wear.feature.settings.WearSettingsPaneScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearSettingsSecurityScreen() {
    val items = rememberSettingsSecurityItems()
    WearSettingsPaneScaffold(
        title = stringResource(Res.string.settings_security_header_title),
        items = items,
    )
}
