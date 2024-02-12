package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent

@Composable
fun JustGetMyDataServicesScreen() {
    NavigationRouter(
        id = JustGetMyDataServicesRoute.ROUTER_NAME,
        initial = JustGetMyDataListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(backStack)
    }
}
