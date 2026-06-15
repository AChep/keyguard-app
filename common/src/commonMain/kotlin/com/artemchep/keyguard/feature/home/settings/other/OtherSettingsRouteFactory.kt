package com.artemchep.keyguard.feature.home.settings.other

import com.artemchep.keyguard.feature.navigation.Route

interface OtherSettingsRouteFactory {
    fun create(): Route
}

object OtherSettingsRouteFactoryDefault : OtherSettingsRouteFactory {
    override fun create(): Route {
        return OtherSettingsRouteImpl
    }
}
