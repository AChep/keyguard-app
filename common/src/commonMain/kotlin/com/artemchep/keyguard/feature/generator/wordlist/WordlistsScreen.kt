package com.artemchep.keyguard.feature.generator.wordlist

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.generator.wordlist.list.WordlistListRoute
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.screenMaxWidthCompact

@Composable
fun WordlistsScreen() {
    NavigationRouter(
        id = WordlistsRoute.ROUTER_NAME,
        initial = WordlistListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(
            backStack,
            detailPaneMaxWidth = screenMaxWidthCompact,
        )
    }
}
