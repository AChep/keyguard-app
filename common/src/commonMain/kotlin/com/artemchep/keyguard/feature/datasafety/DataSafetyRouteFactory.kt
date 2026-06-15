package com.artemchep.keyguard.feature.datasafety

import com.artemchep.keyguard.feature.navigation.Route

interface DataSafetyRouteFactory {
    fun create(): Route
}

object DataSafetyRouteFactoryDefault : DataSafetyRouteFactory {
    override fun create(): Route {
        return DataSafetyRoute
    }
}
