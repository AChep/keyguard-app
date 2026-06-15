package com.artemchep.keyguard.feature.license

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object LicenseRouteImpl : Route {
    @Composable
    override fun Content() {
        LicenseScreen()
    }
}
