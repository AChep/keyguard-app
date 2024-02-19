package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.screenMaxWidthCompact

@Composable
fun TwoFaServicesScreen() {
    NavigationRouter(
        id = JustDeleteMeServicesRoute.ROUTER_NAME,
        initial = JustDeleteMeServiceListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(
            backStack,
            detailPaneMaxWidth = screenMaxWidthCompact,
        )
    }
}
