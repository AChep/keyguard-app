package com.artemchep.keyguard.wear.feature.settings.other

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearSettingsOtherRoute : Route {
    @Composable
    override fun Content() {
        WearSettingsOtherScreen()
    }
}
