package com.artemchep.keyguard.android.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.artemchep.keyguard.android.CompanionAuthActivity
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.LoginContentSkeleton
import com.artemchep.keyguard.feature.auth.bitwarden.LoginScaffold
import com.artemchep.keyguard.feature.auth.bitwarden.produceBitwardenLoginScreenState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.LoginTwofaScreenContent
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.produceLoginTwofaScreenState
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenEnvironment
import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenPayload
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.login
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

internal data class CompanionBitwardenLoginRoute(
    val request: CompanionAuthActivity.Request,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        CompanionBitwardenLoginScreen(
            request = request,
            transmitter = transmitter,
        )
    }
}

internal data class CompanionBitwardenLoginTwofaRoute(
    val request: CompanionAuthActivity.Request,
    val args: BitwardenLoginTwofaRoute.Args,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        CompanionBitwardenTwofaScreen(
            request = request,
            args = args,
            transmitter = transmitter,
        )
    }
}

@Composable
private fun CompanionBitwardenLoginScreen(
    request: CompanionAuthActivity.Request,
    transmitter: RouteResultTransmitter<Unit>,
) = with(localDI().direct) {
    val companionAddAccount = remember(request) {
        CompanionBitwardenAddAccount(
            request = request,
            companionAuthBridge = instance(),
            cryptoGenerator = instance(),
            base64Service = instance(),
            deviceIdUseCase = instance(),
            httpClient = instance(),
            json = instance(),
        )
    }
    val state = produceBitwardenLoginScreenState(
        addAccount = companionAddAccount,
        cipherUnsecureUrlCheck = instance(),
        confirmationRouteFactory = instance(),
        args = BitwardenLoginRoute.Args(),
        screenKey = "bitwardenlogin.companion",
    )
    when (state) {
        Loadable.Loading -> {
            LoginContentSkeleton()
        }

        is Loadable.Ok -> {
            val controller = LocalNavigationController.current
            LaunchedEffect(state.value) {
                state.value.effects.onSuccessFlow.collect {
                    transmitter(Unit)
                }
            }
            LaunchedEffect(state.value) {
                state.value.effects.onErrorFlow.collect { error ->
                    when (error) {
                        is BitwardenLoginEvent.Error.OtpRequired -> {
                            val route = registerRouteResultReceiver(
                                CompanionBitwardenLoginTwofaRoute(
                                    request = request,
                                    args = error.args,
                                ),
                            ) {
                                controller.queue(NavigationIntent.Pop)
                                transmitter(Unit)
                            }
                            controller.queue(NavigationIntent.NavigateToRoute(route))
                        }
                    }
                }
            }
            LoginScaffold(
                loginState = state.value,
            )
        }
    }
}

@Composable
private fun CompanionBitwardenTwofaScreen(
    request: CompanionAuthActivity.Request,
    args: BitwardenLoginTwofaRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
) = with(localDI().direct) {
    val companionAddAccount = remember(request) {
        CompanionBitwardenAddAccount(
            request = request,
            companionAuthBridge = instance(),
            cryptoGenerator = instance(),
            base64Service = instance(),
            deviceIdUseCase = instance(),
            httpClient = instance(),
            json = instance(),
        )
    }
    val state = produceLoginTwofaScreenState(
        cryptoGenerator = instance(),
        base64Service = instance(),
        deeplinkService = instance(),
        json = instance(),
        addAccount = companionAddAccount,
        requestEmailTfa = instance(),
        args = args,
        transmitter = transmitter,
    )
    LoginTwofaScreenContent(
        state = state,
    )
}

private class CompanionBitwardenAddAccount(
    private val request: CompanionAuthActivity.Request,
    private val companionAuthBridge: CompanionAuthBridgeAndroid,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val deviceIdUseCase: DeviceIdUseCase,
    private val httpClient: HttpClient,
    private val json: Json,
) : AddAccount {
    override fun invoke(
        accountId: String?,
        env: ServerEnv,
        twoFactorToken: ServerTwoFactorToken?,
        clientSecret: String?,
        email: String,
        password: String,
    ) = ioEffect(Dispatchers.Default) {
        val (cryptoSecrets, apiSecrets) = login(
            deviceIdUseCase = deviceIdUseCase,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
            httpClient = httpClient,
            json = json,
            env = env,
            twoFactorToken = twoFactorToken,
            clientSecret = clientSecret?.takeUnless { it.isBlank() },
            email = email,
            password = password,
        )
        runCompanionTransfer(
            notifyError = { error, message ->
                companionAuthBridge.notifyErrorFromPhone(
                    requestId = request.requestId,
                    provider = request.provider,
                    error = error,
                    message = message,
                )
            },
        ) {
            companionAuthBridge.completeBitwardenOnPhone(
                requestId = request.requestId,
                payload = CompanionBitwardenPayload(
                    env = CompanionBitwardenEnvironment.of(env),
                    email = email,
                    masterKeyBase64 = base64Service.encodeToString(cryptoSecrets.masterKey),
                    passwordKeyBase64 = base64Service.encodeToString(cryptoSecrets.passwordKey),
                    encryptionKeyBase64 = base64Service.encodeToString(cryptoSecrets.encryptionKey),
                    macKeyBase64 = base64Service.encodeToString(cryptoSecrets.macKey),
                    refreshToken = apiSecrets.refreshToken,
                    accessToken = apiSecrets.accessToken,
                    accessTokenExpirationDate = apiSecrets.accessTokenExpiryDate.toString(),
                ),
            )
        }
        AccountId(request.requestId)
    }
}
