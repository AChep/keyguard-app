package com.artemchep.keyguard.feature.home.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.vault.screen.VaultListRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeVisualStack
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent

@Composable
fun VaultScreen(
    args: VaultRoute.Args,
) {
    val initialRoute = remember(args) {
        VaultListRoute(
            args = args,
        )
    }
    // Vault screen actually does not add any depth to the
    // navigation stack, it just renders sub-windows.
    val visualStack = LocalNavigationNodeVisualStack.current
        .run {
            removeAt(lastIndex)
        }
    CompositionLocalProvider(
        LocalNavigationNodeVisualStack provides visualStack,
    ) {
        NavigationRouter(
            id = "vault",
            initial = initialRoute,
        ) { backStack ->
            TwoPaneNavigationContent(backStack)
        }
    }
}
