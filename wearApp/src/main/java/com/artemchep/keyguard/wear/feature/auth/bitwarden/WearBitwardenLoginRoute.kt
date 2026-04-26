package com.artemchep.keyguard.wear.feature.auth.bitwarden

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.wear.feature.WearManualLoginScreen
import com.artemchep.keyguard.wear.feature.auth.bitwarden.twofactor.WearBitwardenLoginTwofaRoute

@Stable
data class WearBitwardenLoginRoute(
    val args: BitwardenLoginRoute.Args = BitwardenLoginRoute.Args(),
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        val navigationController = LocalNavigationController.current
        WearManualLoginScreen(
            args = args,
            onSuccess = {
                transmitter(Unit)
            },
            onTwoFactor = { twoFactorArgs ->
                val route = registerRouteResultReceiver(
                    route = WearBitwardenLoginTwofaRoute(args = twoFactorArgs),
                ) {
                    navigationController.queue(NavigationIntent.Pop)
                    transmitter(Unit)
                }
                navigationController.queue(NavigationIntent.NavigateToRoute(route))
            },
        )
    }
}
