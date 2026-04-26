package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.onboarding.OnboardingRouteFactory
import com.artemchep.keyguard.wear.feature.onboarding.WearOnboardingRoute

object OnboardingRouteFactoryWear : OnboardingRouteFactory {
    override fun create(): Route {
        return WearOnboardingRoute
    }
}
