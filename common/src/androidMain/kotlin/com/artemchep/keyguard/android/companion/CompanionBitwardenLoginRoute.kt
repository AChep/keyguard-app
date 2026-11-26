package com.artemchep.keyguard.android.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.artemchep.keyguard.android.CompanionAuthActivity
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginScreen
import com.artemchep.keyguard.feature.auth.bitwarden.LoginContentSkeleton
import com.artemchep.keyguard.feature.auth.bitwarden.produceBitwardenLoginScreenState
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenEnvironment
import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenPayload
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.login
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProvider
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.compose.currentKoinScope

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

@Composable
private fun CompanionBitwardenLoginScreen(
    request: CompanionAuthActivity.Request,
    transmitter: RouteResultTransmitter<Unit>,
) = with(currentKoinScope()) {
    // The phone may sign the watch in while its own vault is
    // locked, so the screen can not use the transport of the vault.
    val dependencies = remember {
        get<CompanionBitwardenLoginDependencies>()
    }
    DisposableEffect(dependencies) {
        onDispose {
            dependencies.close()
        }
    }
    val companionAddAccount = remember(request) {
        CompanionBitwardenAddAccount(
            request = request,
            companionAuthBridge = dependencies.companionAuthBridge,
            cryptoGenerator = dependencies.cryptoGenerator,
            base64Service = dependencies.base64Service,
            deviceIdUseCase = dependencies.deviceIdUseCase,
            httpClientProvider = dependencies.httpClientProvider,
            json = dependencies.json,
        )
    }
    val state = produceBitwardenLoginScreenState(
        addAccount = companionAddAccount,
        requestEmailTfa = dependencies.requestEmailTfa,
        // The companion account is sent to the watch,
        // while SSO saves the account on this device.
        isSsoAvailable = false,
        cipherUnsecureUrlCheck = dependencies.cipherUnsecureUrlCheck,
        dateFormatter = dependencies.dateFormatter,
        fileService = dependencies.fileService,
        mutualTlsService = dependencies.mutualTlsService,
        confirmationRouteFactory = dependencies.confirmationRouteFactory,
        discoverBitwardenServer = dependencies.discoverBitwardenServer,
        args = BitwardenLoginRoute.Args(),
        screenKey = "bitwardenlogin.companion",
        // The watch can not use a certificate whose
        // private key stays in the store of this phone.
        mutualTlsSystemStore = null,
    )
    state.fold(
        ifLoading = {
            LoginContentSkeleton()
        },
        ifOk = { okState ->
            // The 2FA continuation completes
            // the login with the companion account.
            BitwardenLoginScreen(
                transmitter = transmitter,
                state = okState,
            )
        },
    )
}

private class CompanionBitwardenAddAccount(
    private val request: CompanionAuthActivity.Request,
    private val companionAuthBridge: CompanionAuthBridgeAndroid,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val deviceIdUseCase: DeviceIdUseCase,
    private val httpClientProvider: BitwardenHttpClientProvider,
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
            httpClient = httpClientProvider.client(env),
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
