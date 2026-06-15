package com.artemchep.keyguard.wear.feature.license

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearLicenseRoute : Route {
    @Composable
    override fun Content() {
        WearLicenseScreen()
    }
}
