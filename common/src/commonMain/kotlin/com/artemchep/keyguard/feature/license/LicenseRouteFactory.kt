package com.artemchep.keyguard.feature.license

import com.artemchep.keyguard.feature.navigation.Route

interface LicenseRouteFactory {
    fun create(): Route
}

object LicenseRouteFactoryDefault : LicenseRouteFactory {
    override fun create(): Route {
        return LicenseRouteImpl
    }
}
