package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

actual object ScanQrRoute : RouteForResult<String> {
    @Composable
    actual override fun Content(transmitter: RouteResultTransmitter<String>) {
    }
}
