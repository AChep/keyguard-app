package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import com.artemchep.keyguard.feature.navigation.RouteForResult

interface BitwardenLoginTwofaRouteFactory {
    fun create(
        args: BitwardenLoginTwofaRoute.Args,
    ): RouteForResult<Unit>
}

object BitwardenLoginTwofaRouteFactoryDefault : BitwardenLoginTwofaRouteFactory {
    override fun create(
        args: BitwardenLoginTwofaRoute.Args,
    ): RouteForResult<Unit> {
        return BitwardenLoginTwofaRoute(
            args = args,
        )
    }
}
