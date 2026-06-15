package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRouteFactory
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.wear.feature.auth.bitwarden.twofactor.WearBitwardenLoginTwofaRoute

object BitwardenLoginTwofaRouteFactoryWear : BitwardenLoginTwofaRouteFactory {
    override fun create(
        args: BitwardenLoginTwofaRoute.Args,
    ): RouteForResult<Unit> {
        return WearBitwardenLoginTwofaRoute(
            args = args,
        )
    }
}
