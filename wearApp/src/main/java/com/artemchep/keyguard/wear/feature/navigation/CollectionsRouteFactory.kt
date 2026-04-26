package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.vault.collections.WearCollectionsRoute

object CollectionsRouteFactoryWear : CollectionsRouteFactory {
    override fun create(args: CollectionsRoute.Args): Route {
        return WearCollectionsRoute(args = args)
    }
}
