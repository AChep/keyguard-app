package com.artemchep.keyguard.feature.auth.login.otp

import androidx.compose.runtime.Composable
import arrow.core.Either
import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProvider
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.Duration

@Composable
fun produceLoginTwofaScreenState(
    args: LoginTwofaRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
) = with(localDI().direct) {
    produceLoginTwofaScreenState(
        cryptoGenerator = instance(),
        base64Service = instance(),
        deeplinkService = instance(),
        json = instance(),
        addAccount = instance(),
        requestEmailTfa = instance(),
        args = args,
        transmitter = transmitter,
    )
}

@Composable
fun produceLoginTwofaScreenState(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    deeplinkService: DeeplinkService,
    json: Json,
    addAccount: AddAccount,
    requestEmailTfa: RequestEmailTfa,
    args: LoginTwofaRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
): TwoFactorState = produceScreenState(
    key = "login_otp",
    initial = TwoFactorState(
        loadingState = MutableStateFlow(false),
        state = LoginTwofaState.Skeleton,
    ),
    args = arrayOf(
        addAccount,
        requestEmailTfa,
        args,
        transmitter,
    ),
) {
    val actionExecutor = screenExecutor()

    val providersComparator = compareBy<TwoFactorProvider>(
        { !it.supported }, // supported items first
        { -it.priority }, // higher priority first
    )
    val providers = args
        .providers
        .mapNotNull { TwoFactorProvider.state[it.type] }
        .sortedWith(providersComparator)

    val providerTypeSink = mutablePersistedFlow("provider_type") {
        providers.firstOrNull()?.type
    }

    val providerItemsFlow = providerTypeSink
        .map { selectedProviderType ->
            providers.map { provider ->
                val checked = provider.type == selectedProviderType
                TwoFactorProviderItem(
                    key = provider.type.name,
                    title = translate(provider.name),
                    checked = checked,
                    onClick = {
                        providerTypeSink.value = provider.type
                    },
                )
            }
        }
    val providerCache = mutableMapOf<TwoFactorProviderType?, Flow<LoginTwofaState>>()
    val providerStateFlow = providerTypeSink
        .flatMapLatest { providerType ->
            val providerArgs = args
                .providers
                .firstOrNull { providerType == it.type }
            // should not happen
                ?: return@flatMapLatest createStateFlowForUnsupported(
                    providerType = null,
                    args = args,
                )
            val stateFlow = providerCache.getOrPut(providerType) {
                val supported = TwoFactorProvider.state[providerType]?.supported
                if (supported != true) {
                    // This might happen if platforms have different
                    // supported 2FA providers.
                    return@getOrPut createStateFlowForUnsupported(
                        providerType = providerType,
                        args = args,
                    )
                }

                when (providerType) {
                    TwoFactorProviderType.Authenticator ->
                        createStateFlowForAuthenticator(
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.Authenticator,
                            transmitter = transmitter,
                        )

                    TwoFactorProviderType.YubiKey ->
                        createStateFlowForYubiKey(
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.YubiKey,
                            transmitter = transmitter,
                        )

                    TwoFactorProviderType.Email ->
                        createStateFlowForEmail(
                            cryptoGenerator = cryptoGenerator,
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            requestEmailTfa = requestEmailTfa,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.Email,
                            transmitter = transmitter,
                        )

                    TwoFactorProviderType.EmailNewDevice ->
                        createStateFlowForEmailNewDevice(
                            cryptoGenerator = cryptoGenerator,
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            requestEmailTfa = requestEmailTfa,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.EmailNewDevice,
                            transmitter = transmitter,
                        )

                    TwoFactorProviderType.Duo ->
                        createStateFlowForDuo(
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.Duo,
                            transmitter = transmitter,
                        )

                    TwoFactorProviderType.OrganizationDuo ->
                        createStateFlowForOrganizationDuo(
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.OrganizationDuo,
                            transmitter = transmitter,
                        )

                    TwoFactorProviderType.Fido2WebAuthn ->
                        createStateFlowForFido2WebAuthn(
                            cryptoGenerator = cryptoGenerator,
                            base64Service = base64Service,
                            deeplinkService = deeplinkService,
                            json = json,
                            actionExecutor = actionExecutor,
                            addAccount = addAccount,
                            args = args,
                            provider = providerArgs as TwoFactorProviderArgument.Fido2WebAuthn,
                            transmitter = transmitter,
                        )

                    else ->
                        // Show unsupported dialog for every other provider
                        // for now.
                        createStateFlowForUnsupported(
                            providerType = providerType,
                            args = args,
                        )
                }
            }
            stateFlow
        }
    combine(
        providerItemsFlow,
        providerStateFlow,
    ) { providerItems, providerState ->
        TwoFactorState(
            providers = providerItems,
            loadingState = actionExecutor.isExecutingFlow,
            state = providerState,
        )
    }
}

private fun RememberStateFlowScope.createStateFlowForUnsupported(
    providerType: TwoFactorProviderType?,
    args: LoginTwofaRoute.Args,
): Flow<LoginTwofaState> {
    val webVaultUrl = args.env.buildWebVaultUrl()
    val onLaunchWebVault = try {
        // Check if the web vault url is a
        // valid url.
        Url(webVaultUrl)
        // if so, then
        val onClick = {
            val intent = NavigationIntent.NavigateToBrowser(
                url = webVaultUrl,
            )
            navigate(intent)
        }
        onClick
    } catch (e: URLParserException) {
        null
    }
    val defaultState = LoginTwofaState.Unsupported(
        providerType = providerType,
        onLaunchWebVault = onLaunchWebVault,
        primaryAction = null,
    )
    return flowOf(
        value = defaultState,
    )
}

private fun RememberStateFlowScope.createStateFlowForAuthenticator(
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.Authenticator,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> {
    val codeSink = mutablePersistedFlow("authenticator.code") { "" }
    val codeState = mutableComposeState(codeSink)

    val codeValidatedFlow = codeSink
        .map { rawCode ->
            val code = rawCode
                .trim()
            if (code.isEmpty()) {
                Validated.Failure(
                    model = code,
                    error = translate(Res.string.error_must_not_be_blank),
                )
            } else {
                Validated.Success(
                    model = code,
                )
            }
        }

    val rememberMeSink = mutablePersistedFlow("remember_me") { false }

    return combine(
        codeValidatedFlow,
        actionExecutor.isExecutingFlow,
        rememberMeSink
            .map { checked ->
                LoginTwofaState.Authenticator.RememberMe(
                    checked = checked,
                    onChange = rememberMeSink::value::set,
                )
            },
    ) { codeValidated, isLoading, rememberMe ->
        val canLogin = codeValidated is Validated.Success &&
                !isLoading
        val primaryAction = LoginTwofaState.PrimaryAction(
            text = translate(Res.string.continue_),
            icon = if (isLoading) {
                LoginTwofaState.PrimaryAction.Icon.LOADING
            } else {
                LoginTwofaState.PrimaryAction.Icon.LOGIN
            },
            onClick = if (canLogin) {
                // lambda
                {
                    val io = addAccount(
                        args.accountId,
                        args.env,
                        ServerTwoFactorToken(
                            token = codeValidated.model,
                            provider = com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType.Authenticator,
                            remember = rememberMe.checked,
                        ),
                        args.clientSecret,
                        args.email,
                        args.password,
                    ).effectTap {
                        transmitter(Unit)
                    }
                    actionExecutor.execute(io)
                }
            } else {
                null
            },
        )
        LoginTwofaState.Authenticator(
            code = TextFieldModel2.of(
                state = codeState,
                validated = codeValidated,
            ),
            rememberMe = rememberMe,
            primaryAction = primaryAction,
        )
    }
}

private fun RememberStateFlowScope.createStateFlowForYubiKey(
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.YubiKey,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> {
    val rememberMeSink = mutablePersistedFlow("remember_me") { false }

    fun onComplete(
        token: String,
        remember: Boolean,
    ) {
        val io = addAccount(
            args.accountId,
            args.env,
            ServerTwoFactorToken(
                token = token,
                provider = TwoFactorProviderType.YubiKey,
                remember = remember,
            ),
            args.clientSecret,
            args.email,
            args.password,
        ).effectTap {
            transmitter(Unit)
        }
        actionExecutor.execute(io)
    }

    return combine(
        actionExecutor.isExecutingFlow,
        rememberMeSink
            .map { checked ->
                LoginTwofaState.YubiKey.RememberMe(
                    checked = checked,
                    onChange = rememberMeSink::value::set,
                )
            },
    ) { isLoading, rememberMe ->
        val canLogin = !isLoading
        LoginTwofaState.YubiKey(
            onComplete = { event: Either<Throwable, String> ->
                event.fold(
                    ifLeft = {
                        action {
                            val msg = ToastMessage(
                                title = translate(Res.string.yubikey_error_failed_to_read),
                                type = ToastMessage.Type.ERROR,
                            )
                            message(msg)
                        }
                    },
                    ifRight = { token ->
                        onComplete(
                            token = token,
                            remember = rememberMe.checked,
                        )
                    },
                )
            }.takeIf { canLogin },
            rememberMe = rememberMe,
            primaryAction = null,
        )
    }
}

private fun RememberStateFlowScope.createStateFlowForEmail(
    cryptoGenerator: CryptoGenerator,
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    requestEmailTfa: RequestEmailTfa,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.Email,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> {
    val codeSink = mutablePersistedFlow("email.code") { "" }
    val codeState = mutableComposeState(codeSink)

    val codeValidatedFlow = codeSink
        .map { rawCode ->
            val code = rawCode
                .trim()
            if (code.isEmpty()) {
                Validated.Failure(
                    model = code,
                    error = translate(Res.string.error_must_not_be_blank),
                )
            } else {
                Validated.Success(
                    model = code,
                )
            }
        }

    val rememberMeSink = mutablePersistedFlow("remember_me") { false }

    val onResendFlow = createResendFlow(
        cryptoGenerator = cryptoGenerator,
        prefix = "email.send_email",
        io = requestEmailTfa(
            args.env,
            args.email,
            args.password,
        ).effectTap {
            val model = ToastMessage(
                title = translate(Res.string.requested_verification_email),
            )
            message(model)
        },
    )

    return combine(
        codeValidatedFlow,
        actionExecutor.isExecutingFlow,
        rememberMeSink
            .map { checked ->
                LoginTwofaState.Email.RememberMe(
                    checked = checked,
                    onChange = rememberMeSink::value::set,
                )
            },
        onResendFlow,
    ) { codeValidated, isLoading, rememberMe, onResend ->
        val canLogin = codeValidated is Validated.Success &&
                !isLoading
        val primaryAction = LoginTwofaState.PrimaryAction(
            text = translate(Res.string.continue_),
            icon = if (isLoading) {
                LoginTwofaState.PrimaryAction.Icon.LOADING
            } else {
                LoginTwofaState.PrimaryAction.Icon.LOGIN
            },
            onClick = if (canLogin) {
                // lambda
                {
                    val io = addAccount(
                        args.accountId,
                        args.env,
                        ServerTwoFactorToken(
                            token = codeValidated.model,
                            provider = com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType.Email,
                            remember = rememberMe.checked,
                        ),
                        args.clientSecret,
                        args.email,
                        args.password,
                    ).effectTap {
                        transmitter(Unit)
                    }
                    actionExecutor.execute(io)
                }
            } else {
                null
            },
        )
        LoginTwofaState.Email(
            email = provider.email,
            emailResend = onResend,
            code = TextFieldModel2.of(
                state = codeState,
                validated = codeValidated,
            ),
            rememberMe = rememberMe,
            primaryAction = primaryAction,
        )
    }
}

private fun RememberStateFlowScope.createStateFlowForEmailNewDevice(
    cryptoGenerator: CryptoGenerator,
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    requestEmailTfa: RequestEmailTfa,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.EmailNewDevice,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> {
    val codeSink = mutablePersistedFlow("email_new_device.code") { "" }
    val codeState = mutableComposeState(codeSink)

    val codeValidatedFlow = codeSink
        .map { rawCode ->
            val code = rawCode
                .trim()
            if (code.isEmpty()) {
                Validated.Failure(
                    model = code,
                    error = translate(Res.string.error_must_not_be_blank),
                )
            } else {
                Validated.Success(
                    model = code,
                )
            }
        }

    val onResendFlow = createResendFlow(
        cryptoGenerator = cryptoGenerator,
        prefix = "email_new_device.send_email",
        io = requestEmailTfa(
            args.env,
            args.email,
            args.password,
        ).effectTap {
            val model = ToastMessage(
                title = translate(Res.string.requested_verification_email),
            )
            message(model)
        },
    )

    return combine(
        codeValidatedFlow,
        actionExecutor.isExecutingFlow,
        onResendFlow,
    ) { codeValidated, isLoading, onResend ->
        val canLogin = codeValidated is Validated.Success &&
                !isLoading
        val primaryAction = LoginTwofaState.PrimaryAction(
            text = translate(Res.string.continue_),
            icon = if (isLoading) {
                LoginTwofaState.PrimaryAction.Icon.LOADING
            } else {
                LoginTwofaState.PrimaryAction.Icon.LOGIN
            },
            onClick = if (canLogin) {
                // lambda
                {
                    val io = addAccount(
                        args.accountId,
                        args.env,
                        ServerTwoFactorToken(
                            token = codeValidated.model,
                            provider = TwoFactorProviderType.EmailNewDevice,
                            remember = false,
                        ),
                        args.clientSecret,
                        args.email,
                        args.password,
                    ).effectTap {
                        transmitter(Unit)
                    }
                    actionExecutor.execute(io)
                }
            } else {
                null
            },
        )
        LoginTwofaState.EmailNewDevice(
            email = args.email,
            emailResend = null, // TODO: Add a support for resending the email
            code = TextFieldModel2.of(
                state = codeState,
                validated = codeValidated,
            ),
            primaryAction = primaryAction,
        )
    }
}

private fun RememberStateFlowScope.createStateFlowForDuo(
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.Duo,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> = createStateFlowForDuo(
    actionExecutor = actionExecutor,
    addAccount = addAccount,
    args = args,
    providerHost = provider.host,
    providerSignature = provider.signature,
    providerType = TwoFactorProviderType.Duo,
    transmitter = transmitter,
)

private fun RememberStateFlowScope.createStateFlowForOrganizationDuo(
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.OrganizationDuo,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> = createStateFlowForDuo(
    actionExecutor = actionExecutor,
    addAccount = addAccount,
    args = args,
    providerHost = provider.host,
    providerSignature = provider.signature,
    providerType = TwoFactorProviderType.OrganizationDuo,
    transmitter = transmitter,
)

private fun RememberStateFlowScope.createStateFlowForDuo(
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    args: LoginTwofaRoute.Args,
    providerHost: String?,
    providerSignature: String?,
    providerType: TwoFactorProviderType,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> {
    val authUrl = kotlin.run {
        val url = args.env.buildWebVaultUrl()
        URLBuilder(Url(url)).apply {
            appendPathSegments("duo-connector.html")
            parameters.apply {
                if (providerHost != null) append("host", providerHost)
                if (providerSignature != null) append("request", providerSignature)
            }
        }.build()
    }

    val rememberMeSink = mutablePersistedFlow("remember_me") { false }

    fun onComplete(
        result: Either<Throwable, String>,
    ) {
        result.fold(
            ifLeft = { e ->
                val text = e.localizedMessage ?: e.message
                val msg = ToastMessage(
                    type = ToastMessage.Type.ERROR,
                    title = text.orEmpty(),
                )
                message(msg)
            },
            ifRight = { token ->
                val io = addAccount(
                    args.accountId,
                    args.env,
                    ServerTwoFactorToken(
                        token = token,
                        provider = providerType,
                        remember = rememberMeSink.value,
                    ),
                    args.clientSecret,
                    args.email,
                    args.password,
                ).effectTap {
                    transmitter(Unit)
                }
                actionExecutor.execute(io)
            },
        )
    }

    return combine(
        actionExecutor.isExecutingFlow,
        rememberMeSink
            .map { checked ->
                LoginTwofaState.Duo.RememberMe(
                    checked = checked,
                    onChange = rememberMeSink::value::set,
                )
            },
    ) { isLoading, rememberMe ->
        val canLogin = !isLoading
        LoginTwofaState.Duo(
            authUrl = authUrl.toString(),
            error = null,
            rememberMe = rememberMe,
            onComplete = ::onComplete
                .takeIf { canLogin },
        )
    }
}

private suspend fun RememberStateFlowScope.createStateFlowForFido2WebAuthn(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    deeplinkService: DeeplinkService,
    json: Json,
    actionExecutor: LoadingTask,
    addAccount: AddAccount,
    args: LoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.Fido2WebAuthn,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<LoginTwofaState> {
    val errorSink = mutablePersistedFlow("fido2webauthn.error") { "" }

    val callbackUrl = "keyguard://webauthn-callback"
    val callbackUrls = setOf(
        callbackUrl,
        // Bitwarden hardcoded a callback URL internally, so we have to
        // check for that schema too, to support older installations.
        // https://github.com/bitwarden/clients/pull/6469
        "bitwarden://webauthn-callback",
    )
    val authUrl = kotlin.run {
        val url = args.env.buildWebVaultUrl()
        val data = buildJsonObject {
            val responseData = provider.json
                ?.let { json.encodeToString(it) }
            if (responseData != null) {
                put("data", responseData)
            }
            put("callbackUri", callbackUrl)
            put("headerText", translate(Res.string.fido2webauthn_web_title))
            put("btnText", translate(Res.string.fido2webauthn_action_go_title))
            put("btnReturnText", translate(Res.string.fido2webauthn_action_return_title))
        }
            .let { json.encodeToString(it) }
            .let { base64Service.encodeToString(it) }
        URLBuilder(Url(url)).apply {
            appendPathSegments("webauthn-mobile-connector.html")
            parameters.apply {
                append("data", data)
                append("parent", callbackUrl)
                append("v", "2")
            }
        }.build()
    }

    val rememberMeSink = mutablePersistedFlow("remember_me") { false }

    fun onComplete(
        result: Either<Throwable, String>,
    ) {
        result.fold(
            ifLeft = { e ->
                val text = e.localizedMessage ?: e.message
                val msg = ToastMessage(
                    type = ToastMessage.Type.ERROR,
                    title = text.orEmpty(),
                )
                message(msg)
            },
            ifRight = { token ->
                val io = addAccount(
                    args.accountId,
                    args.env,
                    ServerTwoFactorToken(
                        token = token,
                        provider = TwoFactorProviderType.Fido2WebAuthn,
                        remember = rememberMeSink.value,
                    ),
                    args.clientSecret,
                    args.email,
                    args.password,
                ).effectTap {
                    transmitter(Unit)
                }
                actionExecutor.execute(io)
            },
        )
    }

    fun onBrowser(
    ) {
        val intent = NavigationIntent.NavigateToBrowser(authUrl.toString())
        navigate(intent)
    }

    return combine(
        deeplinkService
            .getFlow("webauthn-callback")
            .onEach { data ->
                if (data != null) {
                    val result = data.right()
                    onComplete(result)
                }
            },
        errorSink
            .map { error -> error.takeIf { it.isNotBlank() } },
        actionExecutor.isExecutingFlow,
        rememberMeSink
            .map { checked ->
                LoginTwofaState.Fido2WebAuthn.RememberMe(
                    checked = checked,
                    onChange = rememberMeSink::value::set,
                )
            },
    ) { _, error, isLoading, rememberMe ->
        val canLogin = !isLoading
        LoginTwofaState.Fido2WebAuthn(
            authUrl = authUrl.toString(),
            callbackUrls = callbackUrls,
            error = error,
            rememberMe = rememberMe,
            onBrowser = ::onBrowser
                .takeIf { canLogin },
            onComplete = ::onComplete
                .takeIf { canLogin },
        )
    }
}

private fun RememberStateFlowScope.createResendFlow(
    cryptoGenerator: CryptoGenerator,
    prefix: String,
    io: IO<Any?>,
    resendDelay: Duration = with(Duration) { 30L.seconds },
): Flow<(() -> Unit)?> = kotlin.run {
    // A flow of time, past which a user can manually
    // resend a request.
    val canResendAtSink = mutablePersistedFlow("$prefix.resend_at") {
        Instant.DISTANT_FUTURE
    }

    val targetSink = mutablePersistedFlow("$prefix.target") {
        cryptoGenerator.uuid()
    }
    val currentSink = mutablePersistedFlow("$prefix.current") { "" }
    combine(
        targetSink,
        currentSink,
    ) { send, sent ->
        send.takeUnless { it == sent }
    }.filterNotNull()
        .onEach {
            io
                .effectTap {
                    canResendAtSink.value = Clock.System.now() + resendDelay
                }
                .launchIn(this)
        }
        .onEach { key ->
            // Remember the key, so next time when we resubscribe
            // we do not send the email request again.
            currentSink.value = key
        }
        .launchIn(screenScope)

    val resend: () -> Unit = {
        targetSink.value = cryptoGenerator.uuid()
    }

    canResendAtSink
        .flatMapLatest { at ->
            val dt = at - Clock.System.now()
            if (dt.isPositive()) {
                flow {
                    emit(false)
                    delay(dt)
                    emit(true)
                }
            } else {
                flowOf(true)
            }
        }
        .distinctUntilChanged()
        .map { canResend ->
            resend.takeIf { canResend }
        }
}
