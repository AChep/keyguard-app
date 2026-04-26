package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListRouteFactory
import com.artemchep.keyguard.wear.feature.privilegedapp.WearPrivilegedAppListRoute

object PrivilegedAppListRouteFactoryWear : PrivilegedAppListRouteFactory {
    override fun create(): Route {
        return WearPrivilegedAppListRoute
    }
}
