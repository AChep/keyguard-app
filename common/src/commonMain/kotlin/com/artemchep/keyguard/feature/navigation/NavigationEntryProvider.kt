package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * A definition of the distinct application component that
 * makes sense when rendered in a separate window.
 */
@Composable
fun NavigationNode(
    id: String,
    route: Route,
) {
    val parentScope = LocalNavigationController.current.scope
    val entry = remember(id, route) {
        NavigationEntryImpl(
            source = "NavigationNode",
            id = id,
            parent = parentScope,
            route = route,
        )
    }
    DisposableEffect(entry) {
        onDispose {
            entry.destroy()
        }
    }
    NavigationNode(
        entry = entry,
    )
}
