package com.artemchep.keyguard.feature.home.settings.permissions

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object PermissionsSettingsRouteImpl : Route {
    @Composable
    override fun Content() {
        PermissionsSettingsScreen()
    }
}
