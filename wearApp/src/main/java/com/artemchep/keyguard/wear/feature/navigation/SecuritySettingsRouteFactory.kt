package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.settings.security.WearSettingsSecurityRoute

object SecuritySettingsRouteFactoryWear : SecuritySettingsRouteFactory {
    override fun create(): Route {
        return WearSettingsSecurityRoute
    }
}
