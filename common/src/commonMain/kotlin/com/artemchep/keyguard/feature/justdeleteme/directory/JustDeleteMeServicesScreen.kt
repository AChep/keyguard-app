package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent

@Composable
fun TwoFaServicesScreen() {
    NavigationRouter(
        id = JustDeleteMeServicesRoute.ROUTER_NAME,
        initial = JustDeleteMeServiceListRoute,
    ) { backStack ->
        TwoPaneNavigationContent(backStack)
    }
}
