package com.artemchep.keyguard.feature.duplicates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.duplicates.list.DuplicatesListRoute
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.screenMaxWidthCompact

@Composable
fun DuplicatesScreen(
    args: DuplicatesRoute.Args,
) {
    val initialRoute = remember(args) {
        DuplicatesListRoute(
            args = args,
        )
    }
    NavigationRouter(
        id = DuplicatesRoute.ROUTER_NAME,
        initial = initialRoute,
    ) { backStack ->
        TwoPaneNavigationContent(
            backStack,
            detailPaneMaxWidth = screenMaxWidthCompact,
        )
    }
}
