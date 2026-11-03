package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa

data class BitwardenLoginTwofaRoute(
    val args: Args,
) : RouteForResult<Unit> {
    data class Args(
        // two factor
        val providers: List<TwoFactorProviderArgument>,
        // common
        val email: String,
        // server
        val env: ServerEnv,
        val continuation: BitwardenLoginContinuation,
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

/** Authentication state belongs to the originating login presentation, never persisted navigation. */
interface BitwardenLoginContinuation {
    /** Called when the 2FA screen closes, whether or not it authenticated. */
    fun onDismiss() = Unit
    fun authenticate(token: ServerTwoFactorToken?): IO<Unit>
    fun resendEmail(): IO<Unit>
}

/** Continues a login with the master password. */
internal class BitwardenPasswordLoginContinuation(
    private val addAccount: AddAccount,
    private val requestEmailTfa: RequestEmailTfa,
    private val accountId: String?,
    private val env: ServerEnv,
    private val clientSecret: String?,
    private val email: String,
    private val password: String,
) : BitwardenLoginContinuation {
    override fun authenticate(token: ServerTwoFactorToken?): IO<Unit> =
        addAccount(accountId, env, token, clientSecret, email, password).map { Unit }

    override fun resendEmail(): IO<Unit> = requestEmailTfa(env, email, password)
}
