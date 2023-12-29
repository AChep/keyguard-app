package com.artemchep.keyguard.feature.auth.login.otp

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument

data class LoginTwofaRoute(
    val args: Args,
) : RouteForResult<Unit> {
    data class Args(
        val accountId: String? = null,
        // two factor
        val providers: List<TwoFactorProviderArgument>,
        // common
        val clientSecret: String?,
        val email: String,
        val password: String,
        // server
        val env: ServerEnv,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        LoginTwofaScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
