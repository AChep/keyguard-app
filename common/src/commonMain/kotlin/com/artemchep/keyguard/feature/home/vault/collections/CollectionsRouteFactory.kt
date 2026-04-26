package com.artemchep.keyguard.feature.home.vault.collections

import com.artemchep.keyguard.feature.navigation.Route

interface CollectionsRouteFactory {
    fun create(
        args: CollectionsRoute.Args,
    ): Route
}

object CollectionsRouteFactoryDefault : CollectionsRouteFactory {
    override fun create(
        args: CollectionsRoute.Args,
    ): Route {
        return CollectionsRoute(
            args = args,
        )
    }
}
