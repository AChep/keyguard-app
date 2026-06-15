package com.artemchep.keyguard.feature.auth

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.Route

interface AccountViewRouteFactory {
    fun create(
        accountId: AccountId,
    ): Route
}

object AccountViewRouteFactoryDefault : AccountViewRouteFactory {
    override fun create(
        accountId: AccountId,
    ): Route {
        return AccountViewRoute(
            accountId = accountId,
        )
    }
}
