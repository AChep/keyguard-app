package com.artemchep.keyguard.feature.home.settings.permissions

import com.artemchep.keyguard.feature.navigation.Route

interface PermissionsSettingsRouteFactory {
    fun create(): Route
}

object PermissionsSettingsRouteFactoryDefault : PermissionsSettingsRouteFactory {
    override fun create(): Route {
        return PermissionsSettingsRouteImpl
    }
}
