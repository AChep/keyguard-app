package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.settings.other.WearSettingsOtherRoute

object OtherSettingsRouteFactoryWear : OtherSettingsRouteFactory {
    override fun create(): Route {
        return WearSettingsOtherRoute
    }
}
