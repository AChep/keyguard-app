package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
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
    val handle = remember(id, route) {
        val entry = NavigationEntryImpl(
            source = "NavigationNode",
            id = id,
            parent = parentScope,
            route = route,
        )
        NavigationRootEntryHandle(entry)
    }
    NavigationNode(
        entry = handle.entry,
    )
}

/**
 * Destroys the root [entry] once the composition forgets it, or
 * abandons it before it was ever remembered.
 */
private class NavigationRootEntryHandle(
    val entry: NavigationEntry,
) : RememberObserver {
    override fun onRemembered() {
        // Do nothing.
    }

    override fun onForgotten() {
        entry.destroy()
    }

    override fun onAbandoned() {
        entry.destroy()
    }
}
