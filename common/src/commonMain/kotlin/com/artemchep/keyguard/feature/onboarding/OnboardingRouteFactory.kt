package com.artemchep.keyguard.feature.onboarding

import com.artemchep.keyguard.feature.navigation.Route

interface OnboardingRouteFactory {
    fun create(): Route
}

object OnboardingRouteFactoryDefault : OnboardingRouteFactory {
    override fun create(): Route {
        return OnboardingRoute
    }
}
