package com.artemchep.keyguard.wear.feature.settings.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearSettingsSecurityRoute : Route {
    @Composable
    override fun Content() {
        WearSettingsSecurityScreen()
    }
}
