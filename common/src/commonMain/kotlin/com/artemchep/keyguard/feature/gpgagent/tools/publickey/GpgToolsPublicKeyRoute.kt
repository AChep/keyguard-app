package com.artemchep.keyguard.feature.gpgagent.tools.publickey

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope

data class GpgToolsPublicKeyRoute(
    val args: Args,
) : DialogRouteForResult<GpgToolsPublicKeyResult> {
    data class Args(
        val publicKey: String = "",
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<GpgToolsPublicKeyResult>,
    ) {
        GpgToolsPublicKeyScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

sealed interface GpgToolsPublicKeyResult {
    data object Deny : GpgToolsPublicKeyResult

    data class Confirm(
        val publicKey: String,
    ) : GpgToolsPublicKeyResult
}

inline fun RememberStateFlowScope.createGpgToolsPublicKeyDialogIntent(
    args: GpgToolsPublicKeyRoute.Args,
    noinline onConfirm: (String) -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = GpgToolsPublicKeyRoute(
            args = args,
        ),
    ) { result ->
        if (result is GpgToolsPublicKeyResult.Confirm) {
            onConfirm(result.publicKey)
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}
