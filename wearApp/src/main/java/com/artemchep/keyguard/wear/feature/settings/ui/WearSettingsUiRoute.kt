package com.artemchep.keyguard.wear.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearSettingsUiRoute : Route {
    @Composable
    override fun Content() {
        WearSettingsUiScreen()
    }
}
