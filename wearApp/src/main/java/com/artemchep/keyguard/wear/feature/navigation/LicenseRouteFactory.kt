package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.license.LicenseRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.license.WearLicenseRoute

object LicenseRouteFactoryWear : LicenseRouteFactory {
    override fun create(): Route {
        return WearLicenseRoute
    }
}
