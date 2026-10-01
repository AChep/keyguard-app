package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

/** The route of a [NavigationIntent.NavigateToRoute] intent, if it is an [R]. */
internal inline fun <reified R : Any> NavigationIntent.routeOrNull(): R? =
    (this as? NavigationIntent.NavigateToRoute)?.route as? R

/**
 * Unwraps a route that `registerRouteResultReceiver` wrapped: the inner [R] route
 * and the transmitter that delivers its result back to the registered receiver.
 */
internal inline fun <reified R : RouteForResult<T>, T> NavigationIntent.resultRouteOrNull(): Pair<
    R,
    RouteResultTransmitter<T>,
>? {
    val holder = routeOrNull<RouteResultReceiver<*>>()
    val inner = holder?.innerRoute as? R ?: return null
    // Sound: the holder's transmitter shares the inner route's result type.
    @Suppress("UNCHECKED_CAST")
    return inner to (holder.resultTransmitter as RouteResultTransmitter<T>)
}
