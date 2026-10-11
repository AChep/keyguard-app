package com.artemchep.keyguard.feature.gpgagent.tools.keypicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsState
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class GpgToolsKeyPickerRoute(
    val args: Args,
) : DialogRouteForResult<Set<String>> {
    data class Args(
        val keys: List<GpgToolsState.KeyItem>,
        val selectedIds: Set<String>,
        val mode: Mode,
    )

    enum class Mode {
        Recipients,
        Signing,
        OptionalSigning,
    }

    @Composable
    override fun Content(transmitter: RouteResultTransmitter<Set<String>>) {
        GpgToolsKeyPickerScreen(args = args, transmitter = transmitter)
    }
}
