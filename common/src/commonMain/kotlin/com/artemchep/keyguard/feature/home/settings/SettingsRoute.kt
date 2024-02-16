package com.artemchep.keyguard.feature.home.settings

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object SettingsRoute : Route {
    const val ROUTER_NAME = "settings"

    @Composable
    override fun Content() {
        SettingsScreen()
    }
}
