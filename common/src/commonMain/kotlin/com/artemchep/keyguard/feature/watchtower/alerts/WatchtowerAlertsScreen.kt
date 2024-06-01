package com.artemchep.keyguard.feature.watchtower.alerts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeVisualStack
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent

@Composable
fun WatchtowerAlerts(
    args: WatchtowerAlertsRoute.Args,
) {
    val initialRoute = remember(args) {
        WatchtowerNewAlertsRoute(args)
    }
    // Send screen actually does not add any depth to the
    // navigation stack, it just renders sub-windows.
    val visualStack = LocalNavigationNodeVisualStack.current
        .run {
            removeAt(lastIndex)
        }
    CompositionLocalProvider(
        LocalNavigationNodeVisualStack provides visualStack,
    ) {
        NavigationRouter(
            id = WatchtowerAlertsRoute.ROUTER_NAME,
            initial = initialRoute,
        ) { backStack ->
            TwoPaneNavigationContent(backStack)
        }
    }
}
