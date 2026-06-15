package com.artemchep.keyguard.wear.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearSettingsRoute : Route {
    @Composable
    override fun Content() {
        WearSettingsScreen()
    }
}
