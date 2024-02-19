package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.screenMaxWidthCompact

@Composable
fun JustGetMyDataServicesScreen() {
    NavigationRouter(
        id = JustGetMyDataServicesRoute.ROUTER_NAME,
        initial = JustGetMyDataListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(
            backStack,
            detailPaneMaxWidth = screenMaxWidthCompact,
        )
    }
}
