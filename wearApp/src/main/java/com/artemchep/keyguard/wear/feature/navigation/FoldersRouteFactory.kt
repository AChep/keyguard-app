package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.vault.folders.WearFoldersRoute

object FoldersRouteFactoryWear : FoldersRouteFactory {
    override fun create(args: FoldersRoute.Args): Route {
        return WearFoldersRoute(args = args)
    }
}
