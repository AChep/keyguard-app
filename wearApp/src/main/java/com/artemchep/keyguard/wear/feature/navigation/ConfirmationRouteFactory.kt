package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.wear.feature.confirmation.WearConfirmationRoute

object ConfirmationRouteFactoryWear : ConfirmationRouteFactory {
    override fun create(
        args: ConfirmationRoute.Args,
    ): RouteForResult<ConfirmationResult> {
        return WearConfirmationRoute(args = args)
    }
}
