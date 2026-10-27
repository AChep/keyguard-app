package com.artemchep.keyguard.feature.home.settings.environments

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object EnvironmentsRoute : Route {
    override val descriptor get() = RouteDescriptor.Environments

    @Composable
    override fun Content() {
        EnvironmentsSettingsScreen()
    }
}
