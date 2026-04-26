package com.artemchep.keyguard.wear.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearOnboardingRoute : Route {
    @Composable
    override fun Content() {
        WearOnboardingScreen()
    }
}
