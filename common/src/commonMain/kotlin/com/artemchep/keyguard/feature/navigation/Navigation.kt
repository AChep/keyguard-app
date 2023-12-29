package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalBackHost = staticCompositionLocalOf<Boolean> {
    false
}

internal val LocalRoute = staticCompositionLocalOf<Route> {
    throw IllegalStateException("Home layout must be initialized!")
}

@Stable
interface RouteForResult<T> {
    @Composable
    fun Content(transmitter: RouteResultTransmitter<T>)
}

@Stable
interface DialogRouteForResult<T> : RouteForResult<T>

fun <T> registerRouteResultReceiver(
    route: RouteForResult<T>,
    block: (T) -> Unit,
): Route {
    val transmitter: RouteResultTransmitter<T> = object : RouteResultTransmitter<T> {
        override fun invoke(unit: T) {
            block(unit)
        }
    }
    return object : Route {
        @Composable
        override fun Content() {
            route.Content(
                transmitter = transmitter,
            )
        }
    }
}

fun <T> registerRouteResultReceiver(
    route: DialogRouteForResult<T>,
    block: (T) -> Unit,
): DialogRoute {
    val transmitter: RouteResultTransmitter<T> = object : RouteResultTransmitter<T> {
        override fun invoke(unit: T) {
            block(unit)
        }
    }
    return object : DialogRoute {
        @Composable
        override fun Content() {
            route.Content(
                transmitter = transmitter,
            )
        }
    }
}

/**
 * A callback to pass the result back
 * to a caller.
 */
interface RouteResultTransmitter<T> : (T) -> Unit
