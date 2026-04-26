package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.feature.navigation.Route

interface FoldersRouteFactory {
    fun create(
        args: FoldersRoute.Args,
    ): Route
}

object FoldersRouteFactoryDefault : FoldersRouteFactory {
    override fun create(
        args: FoldersRoute.Args,
    ): Route {
        return FoldersRoute(
            args = args,
        )
    }
}
