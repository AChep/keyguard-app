package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * A composable destination owned by the custom navigation stack.
 *
 * Routes intentionally carry the arguments needed to render a screen so the
 * shared code can navigate without platform-specific destination registries.
 */
@Stable
interface Route {
    @Composable
    fun Content()
}

/**
 * A route rendered above the last fullscreen route instead of replacing it.
 */
@Stable
interface DialogRoute : Route

/**
 * Optional marker for routes that can expose a stable external name.
 */
interface SerializableRoute {
    val name: String
}
