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

@Stable
interface RouteResultReceiver<T> {
    val innerRoute: RouteForResult<T>
    val resultTransmitter: RouteResultTransmitter<T>
}

fun <T> registerRouteResultReceiver(
    route: RouteForResult<T>,
    block: (T) -> Unit,
): Route {
    val transmitter: RouteResultTransmitter<T> = object : RouteResultTransmitter<T> {
        override fun invoke(unit: T) {
            block(unit)
        }
    }
    return object : Route, RouteResultReceiver<T> {
        override val innerRoute: RouteForResult<T> get() = route
        override val resultTransmitter: RouteResultTransmitter<T> get() = transmitter

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
    return object : DialogRoute, RouteResultReceiver<T> {
        override val innerRoute: RouteForResult<T> get() = route
        override val resultTransmitter: RouteResultTransmitter<T> get() = transmitter

        @Composable
        override fun Content() {
            route.Content(
                transmitter = transmitter,
            )
        }
    }
}

interface RouteResultTransmitter<T> : (T) -> Unit
