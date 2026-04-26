package com.artemchep.keyguard.feature.home.settings.security

import com.artemchep.keyguard.feature.navigation.Route

interface SecuritySettingsRouteFactory {
    fun create(): Route
}

object SecuritySettingsRouteFactoryDefault : SecuritySettingsRouteFactory {
    override fun create(): Route {
        return SecuritySettingsRouteImpl
    }
}
