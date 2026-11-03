package com.artemchep.keyguard.feature.auth.bitwarden.sso

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.ServerEnv

/** Signs in with SSO, then unlocks the vault with the master password or Key Connector. */
data class BitwardenSsoRoute(
    val args: Args,
) : RouteForResult<Unit> {
    data class Args(
        /** The account to log in again, or `null` to add a new one. */
        val accountId: String? = null,
        val env: ServerEnv,
        val email: String,
        val organizationIdentifier: String? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        BitwardenSsoScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
