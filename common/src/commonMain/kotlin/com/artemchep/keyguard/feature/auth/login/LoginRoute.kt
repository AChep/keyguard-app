package com.artemchep.keyguard.feature.auth.login

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.ServerEnv

data class LoginRoute(
    val args: Args = Args(),
) : RouteForResult<Unit> {
    data class Args(
        val accountId: String? = null,
        // common
        val clientSecret: String? = null,
        val clientSecretEditable: Boolean = true,
        val email: String? = null,
        val emailEditable: Boolean = true,
        val password: String? = null,
        val passwordEditable: Boolean = true,
        // server
        val env: ServerEnv = ServerEnv(),
        val envEditable: Boolean = true,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        LoginScreen(
            transmitter = transmitter,
            args = args,
        )
    }
}
