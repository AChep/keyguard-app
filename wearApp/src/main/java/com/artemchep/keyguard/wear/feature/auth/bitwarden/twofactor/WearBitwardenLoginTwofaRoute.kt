package com.artemchep.keyguard.wear.feature.auth.bitwarden.twofactor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

@Stable
data class WearBitwardenLoginTwofaRoute(
    val args: BitwardenLoginTwofaRoute.Args,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        WearBitwardenLoginTwofaScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
