package com.artemchep.keyguard.wear.feature.auth.bitwarden.twofactor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument

@Stable
data class WearBitwardenLoginTwofaProviderRoute(
    val args: BitwardenLoginTwofaRoute.Args,
    val provider: TwoFactorProviderArgument,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        WearBitwardenLoginTwofaProviderScreen(
            args = args,
            provider = provider,
            transmitter = transmitter,
        )
    }
}
