package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.datasafety.DataSafetyRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.datasafety.WearDataSafetyRoute

object DataSafetyRouteFactoryWear : DataSafetyRouteFactory {
    override fun create(): Route {
        return WearDataSafetyRoute
    }
}
