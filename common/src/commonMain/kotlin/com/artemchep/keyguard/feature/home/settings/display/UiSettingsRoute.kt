package com.artemchep.keyguard.feature.home.settings.display

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object UiSettingsRouteImpl : Route {
    @Composable
    override fun Content() {
        UiSettingsScreen()
    }
}
