package com.artemchep.keyguard.feature.filter

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.filter.list.CipherFiltersListRoute
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.screenMaxWidthCompact

@Composable
fun CipherFiltersScreen() {
    NavigationRouter(
        id = CipherFiltersRoute.ROUTER_NAME,
        initial = CipherFiltersListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(
            backStack,
            detailPaneMaxWidth = screenMaxWidthCompact,
        )
    }
}
