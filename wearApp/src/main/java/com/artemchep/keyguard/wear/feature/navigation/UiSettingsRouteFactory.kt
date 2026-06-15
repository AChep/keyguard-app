package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.settings.ui.WearSettingsUiRoute

object UiSettingsRouteFactoryWear : UiSettingsRouteFactory {
    override fun create(): Route {
        return WearSettingsUiRoute
    }
}
