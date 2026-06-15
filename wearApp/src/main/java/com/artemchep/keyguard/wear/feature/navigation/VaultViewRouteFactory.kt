package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.vault.view.WearVaultViewRoute

object VaultViewRouteFactoryWear : VaultViewRouteFactory {
    override fun create(
        itemId: String,
        accountId: String,
        tag: String?,
    ): Route {
        return WearVaultViewRoute(
            itemId = itemId,
            accountId = accountId,
        )
    }
}
