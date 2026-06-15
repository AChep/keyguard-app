package com.artemchep.keyguard.feature.auth.bitwarden

import com.artemchep.keyguard.feature.navigation.RouteForResult

interface BitwardenLoginRouteFactory {
    fun create(
        args: BitwardenLoginRoute.Args = BitwardenLoginRoute.Args(),
    ): RouteForResult<Unit>
}

object BitwardenLoginRouteFactoryDefault : BitwardenLoginRouteFactory {
    override fun create(
        args: BitwardenLoginRoute.Args,
    ): RouteForResult<Unit> {
        return BitwardenLoginRoute(
            args = args,
        )
    }
}
