package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactory
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.wear.feature.auth.bitwarden.WearBitwardenLoginRoute

object BitwardenLoginRouteFactoryWear : BitwardenLoginRouteFactory {
    override fun create(
        args: BitwardenLoginRoute.Args,
    ): RouteForResult<Unit> {
        return WearBitwardenLoginRoute(
            args = args,
        )
    }
}
