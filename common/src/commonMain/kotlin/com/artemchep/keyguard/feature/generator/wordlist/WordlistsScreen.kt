package com.artemchep.keyguard.feature.generator.wordlist

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.generator.wordlist.list.WordlistListRoute
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent

@Composable
fun WordlistsScreen() {
    NavigationRouter(
        id = WordlistsRoute.ROUTER_NAME,
        initial = WordlistListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(backStack)
    }
}
