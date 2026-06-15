package com.artemchep.keyguard.feature.home.settings.security

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object SecuritySettingsRouteImpl : Route {
    @Composable
    override fun Content() {
        SecuritySettingsScreen()
    }
}
