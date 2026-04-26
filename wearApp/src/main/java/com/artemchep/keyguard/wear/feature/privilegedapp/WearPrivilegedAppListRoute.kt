package com.artemchep.keyguard.wear.feature.privilegedapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearPrivilegedAppListRoute : Route {
    @Composable
    override fun Content() {
        WearPrivilegedAppListScreen()
    }
}
