package com.artemchep.keyguard.feature.home.settings

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object SettingsRoute : Route {
    const val ROUTER_NAME = "settings"

    override val descriptor get() = RouteDescriptor.Settings

    @Composable
    override fun Content() {
        SettingsScreen()
    }
}
