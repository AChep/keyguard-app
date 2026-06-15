package com.artemchep.keyguard.feature.onboarding

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal object OnboardingRoute : Route {
    @Composable
    override fun Content() {
        OnboardingScreen()
    }
}
