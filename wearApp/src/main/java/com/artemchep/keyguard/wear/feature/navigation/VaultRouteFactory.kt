package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.vault.WearVaultListRoute

object VaultRouteFactoryWear : VaultRouteFactory {
    override fun create(args: VaultRoute.Args): Route {
        return WearVaultListRoute(args = args)
    }
}
