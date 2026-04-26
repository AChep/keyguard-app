package com.artemchep.keyguard.feature.privilegedapp

import com.artemchep.keyguard.feature.navigation.Route

interface PrivilegedAppListRouteFactory {
    fun create(): Route
}

object PrivilegedAppListRouteFactoryDefault : PrivilegedAppListRouteFactory {
    override fun create(): Route {
        return PrivilegedAppListRoute
    }
}
