package com.artemchep.keyguard.feature.confirmation.elevatedaccess

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope

class ElevatedAccessRoute(
) : DialogRouteForResult<ElevatedAccessResult> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<ElevatedAccessResult>,
    ) {
        ElevatedAccessScreen(
            transmitter = transmitter,
        )
    }
}

fun RememberStateFlowScope.createElevatedAccessDialogIntent(
    onSuccess: () -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = ElevatedAccessRoute(),
    ) { result ->
        if (result is ElevatedAccessResult.Allow) {
            onSuccess()
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}
