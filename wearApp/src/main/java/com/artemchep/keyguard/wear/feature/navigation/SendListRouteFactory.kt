package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.SendRouteFactory
import com.artemchep.keyguard.wear.feature.send.WearSendListRoute
import com.artemchep.keyguard.wear.feature.vault.WearVaultListRoute

object SendListRouteFactoryWear : SendRouteFactory {
    override fun create(args: SendRoute.Args): Route {
        return WearSendListRoute(args = args)
    }
}
