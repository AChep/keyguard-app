package com.artemchep.keyguard.feature.gpgkey.expiration

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope

data class GpgKeyExpirationRoute(
    val args: Args,
) : DialogRouteForResult<GpgKeyExpirationChange> {
    data class Args(
        val keyInfo: GpgPublicKeyInfo,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<GpgKeyExpirationChange>,
    ) {
        GpgKeyExpirationScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

fun RememberStateFlowScope.requestGpgKeyExpirationChange(
    keyInfo: GpgPublicKeyInfo,
    onConfirm: (GpgKeyExpirationChange) -> Unit,
) {
    val route = registerRouteResultReceiver(
        route = GpgKeyExpirationRoute(
            args = GpgKeyExpirationRoute.Args(
                keyInfo = keyInfo,
            ),
        ),
        block = onConfirm,
    )
    navigate(NavigationIntent.NavigateToRoute(route))
}
