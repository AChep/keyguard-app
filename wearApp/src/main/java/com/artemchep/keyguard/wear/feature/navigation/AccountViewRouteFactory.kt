package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.auth.AccountViewRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.account.WearAccountViewRoute

object AccountViewRouteFactoryWear : AccountViewRouteFactory {
    override fun create(
        accountId: AccountId,
    ): Route {
        return WearAccountViewRoute(
            accountId = accountId,
        )
    }
}
