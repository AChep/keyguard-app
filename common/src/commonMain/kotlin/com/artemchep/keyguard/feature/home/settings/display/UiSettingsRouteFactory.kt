package com.artemchep.keyguard.feature.home.settings.display

import com.artemchep.keyguard.feature.navigation.Route

interface UiSettingsRouteFactory {
    fun create(): Route
}

object UiSettingsRouteFactoryDefault : UiSettingsRouteFactory {
    override fun create(): Route {
        return UiSettingsRouteImpl
    }
}
