package com.artemchep.keyguard.feature.gpgkey.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class GpgUserIdSelectionIdentity(
    val identityId: String,
    val userId: String,
)

data class GpgUserIdSelectionRoute(
    val args: Args,
) : DialogRouteForResult<String> {
    @Immutable
    @Serializable
    data class Args(
        val activeIdentities: List<GpgUserIdSelectionIdentity>,
        val mode: Mode,
    ) {
        @Serializable
        enum class Mode {
            Replacement,
            Revocation,
        }
    }

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<String>,
    ) {
        GpgUserIdSelectionScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

suspend fun RememberStateFlowScope.requestGpgUserIdSelection(
    activeIdentities: List<GpgUserIdSelectionIdentity>,
    mode: GpgUserIdSelectionRoute.Args.Mode,
    onConfirm: (identityId: String) -> Unit,
) {
    val route = registerRouteResultReceiver(
        route = GpgUserIdSelectionRoute(
            args = GpgUserIdSelectionRoute.Args(
                activeIdentities = activeIdentities,
                mode = mode,
            ),
        ),
        block = onConfirm,
    )
    navigate(NavigationIntent.NavigateToRoute(route))
}
