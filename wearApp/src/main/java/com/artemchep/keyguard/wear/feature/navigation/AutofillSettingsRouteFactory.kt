package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.settings.autofill.WearSettingsAutofillRoute

object AutofillSettingsRouteFactoryWear : AutofillSettingsRouteFactory {
    override fun create(): Route {
        return WearSettingsAutofillRoute
    }
}
