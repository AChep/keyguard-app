package com.artemchep.keyguard.feature.apppicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

actual object AppPickerRoute : RouteForResult<AppPickerResult> {
    @Composable
    override fun Content(transmitter: RouteResultTransmitter<AppPickerResult>) {
    }
}
