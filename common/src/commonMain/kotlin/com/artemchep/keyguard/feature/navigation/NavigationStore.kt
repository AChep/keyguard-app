package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.staticCompositionLocalOf

class NavigationStore {
    private var piles = mutableMapOf<String, NavigationPile>()

    fun save(
        navPileId: String,
        navPile: NavigationPile,
    ) {
        piles.put(navPileId, navPile)
    }

    fun pop(navPileId: String) = piles.remove(navPileId)
}

internal val LocalNavigationStore = staticCompositionLocalOf<NavigationStore> {
    NavigationStore()
}
