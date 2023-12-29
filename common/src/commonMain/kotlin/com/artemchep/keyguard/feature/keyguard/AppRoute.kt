package com.artemchep.keyguard.feature.keyguard

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

object AppRoute : Route {
    @Composable
    override fun Content() {
        AppScreen()
    }
}
