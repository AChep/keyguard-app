package com.artemchep.keyguard.feature.home.vault.link

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class CipherLinkPickerRoute(
    val args: Args,
) : DialogRouteForResult<CipherLinkPickerResult> {
    data class Args(
        val accountId: String,
        val excludedCipherId: String? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
    ) {
        CipherLinkPickerScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

sealed interface CipherLinkPickerResult {
    data class Confirm(
        val link: CipherLink,
    ) : CipherLinkPickerResult

    data object Deny : CipherLinkPickerResult
}
