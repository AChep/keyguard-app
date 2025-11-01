package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data object KeePassLoginRoute : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        KeePassLoginScreen(
            transmitter = transmitter,
        )
    }
}
