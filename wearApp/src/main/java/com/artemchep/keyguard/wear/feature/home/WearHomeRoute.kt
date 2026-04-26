package com.artemchep.keyguard.wear.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.settings.WearSettingsScreen

@Stable
object WearHomeRoute : Route {
    @Composable
    override fun Content() {
        WearHomeScreen()
    }
}
