package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

@Stable
class NavigationRouterNode(
    val id: String,
    /**
     * Parent navigation router,
     * if that is available.
     */
    val parent: NavigationRouterNode? = null,
) {
    val childrenState = mutableStateListOf<NavigationRouterNode>()

    val entryState = mutableStateOf<NavigationEntry?>(null)
}

internal val LocalNavigationRouterNode = compositionLocalOf<NavigationRouterNode> {
    NavigationRouterNode(
        id = "root",
        parent = null,
    )
}
