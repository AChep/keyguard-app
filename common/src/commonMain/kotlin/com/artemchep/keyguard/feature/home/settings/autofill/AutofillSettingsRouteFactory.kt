package com.artemchep.keyguard.feature.home.settings.autofill

import com.artemchep.keyguard.feature.navigation.Route

interface AutofillSettingsRouteFactory {
    fun create(): Route
}

object AutofillSettingsRouteFactoryDefault : AutofillSettingsRouteFactory {
    override fun create(): Route {
        return AutofillSettingsRouteImpl
    }
}
