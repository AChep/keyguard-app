package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.feature.navigation.Route

interface VaultViewRouteFactory {
    fun create(
        itemId: String,
        accountId: String,
        tag: String? = null,
    ): Route
}

object VaultViewRouteFactoryDefault : VaultViewRouteFactory {
    override fun create(
        itemId: String,
        accountId: String,
        tag: String?,
    ): Route {
        return VaultViewRoute(
            itemId = itemId,
            accountId = accountId,
            tag = tag,
        )
    }
}
