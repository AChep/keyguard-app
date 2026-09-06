package com.artemchep.keyguard.feature.gpgkey.replacement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.serialization.Serializable

data class GpgUserIdReplacementRoute(
    val args: Args,
) : DialogRouteForResult<String> {
    @Immutable
    @Serializable
    data class Args(
        val oldUserId: String,
        val activeUserIds: List<String>,
        val initialValue: String = "",
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<String>,
    ) {
        GpgUserIdReplacementScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

suspend fun RememberStateFlowScope.requestGpgUserIdReplacementValue(
    oldUserId: String,
    activeUserIds: List<String>,
    onConfirm: (newUserId: String) -> Unit,
) {
    val route = registerRouteResultReceiver(
        route = GpgUserIdReplacementRoute(
            args = GpgUserIdReplacementRoute.Args(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
            ),
        ),
        block = onConfirm,
    )
    navigate(NavigationIntent.NavigateToRoute(route))
}
