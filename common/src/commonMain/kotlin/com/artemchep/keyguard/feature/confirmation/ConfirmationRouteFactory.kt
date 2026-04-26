package com.artemchep.keyguard.feature.confirmation

import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver

interface ConfirmationRouteFactory {
    fun create(
        args: ConfirmationRoute.Args,
    ): RouteForResult<ConfirmationResult>
}

object ConfirmationRouteFactoryDefault : ConfirmationRouteFactory {
    override fun create(
        args: ConfirmationRoute.Args,
    ): RouteForResult<ConfirmationResult> {
        return ConfirmationRoute(args = args)
    }
}

fun ConfirmationRouteFactory.registerRouteResultReceiver(
    args: ConfirmationRoute.Args,
    block: (ConfirmationResult) -> Unit,
): Route {
    val route = create(args = args)
    return if (route is DialogRouteForResult<*>) {
        @Suppress("UNCHECKED_CAST")
        registerRouteResultReceiver(
            route = route as DialogRouteForResult<ConfirmationResult>,
            block = block,
        )
    } else {
        registerRouteResultReceiver(
            route = route,
            block = block,
        )
    }
}
