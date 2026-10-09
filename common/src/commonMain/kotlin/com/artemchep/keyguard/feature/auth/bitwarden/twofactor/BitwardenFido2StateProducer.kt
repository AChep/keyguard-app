package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import arrow.core.Either
import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.feature.fido2.Fido2PromptHost
import com.artemchep.keyguard.feature.fido2.asFido2AppException
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.supportsFido2Assertions
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.util.fido2.Fido2AssertionRequest
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.Fido2Operation
import com.artemchep.keyguard.util.fido2.decodeFido2AssertionResult
import com.artemchep.keyguard.util.fido2.webAuthnJson
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Independent native, browser, retry, and callback actions share the screen's attempt state.
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught") // Report action failures at the UI boundary.
internal suspend fun RememberStateFlowScope.createStateFlowForFido2WebAuthn(
    base64Service: Base64Service,
    deeplinkService: DeeplinkService,
    json: Json,
    addAccount: AddAccount,
    args: BitwardenLoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument.Fido2WebAuthn,
    transmitter: RouteResultTransmitter<Unit>,
    defaultRememberMe: Boolean,
): Flow<BitwardenLoginTwofaState> {
    val rememberMe = mutablePersistedFlow("remember_me") { defaultRememberMe }
    val screen = this
    val nativeAvailable = supportsFido2Assertions()
    val browserAvailable = CurrentPlatform is Platform.Mobile.Android
    val vaultUrl = args.env.buildWebVaultUrl()
    val callbackUrl = "keyguard://webauthn-callback"
    val callbackUrls = setOf(callbackUrl, "bitwarden://webauthn-callback")
    val header = translate(Res.string.fido2webauthn_web_title)
    val button = translate(Res.string.fido2webauthn_action_go_title)
    val returnButton = translate(Res.string.fido2webauthn_action_return_title)
    var initialOptions = provider.json

    // UI callbacks and all attempt state are confined to Main, including on native Apple.
    return channelFlow {
        val prompts = Fido2PromptHost()
        val phase = MutableStateFlow(Fido2LoginPhase.IDLE)
        var job: Job? = null
        var browserRequest: Fido2AssertionRequest? = null
        var generation = 0

        suspend fun nextRequest(): Fido2AssertionRequest? {
            val options = initialOptions.also { initialOptions = null } ?: try {
                addAccount(args.accountId, args.env, null, args.clientSecret, args.email, args.password).bind()
                currentCoroutineContext().ensureActive()
                phase.value = Fido2LoginPhase.COMPLETE
                transmitter(Unit)
                return null
            } catch (error: ApiException) {
                val providers = (error.type as? ApiException.Type.TwoFaRequired)?.providers
                providers?.filterIsInstance<TwoFactorProviderArgument.Fido2WebAuthn>()?.firstOrNull()?.json
                    ?: throw error
            }
            return try {
                bitwardenFido2Request(options, vaultUrl)
            } catch (_: IllegalArgumentException) {
                throw Fido2Exception(Fido2Failure.PROTOCOL).asFido2AppException()
            }
        }

        suspend fun submit(token: String) {
            currentCoroutineContext().ensureActive()
            phase.value = Fido2LoginPhase.SUBMITTING
            addAccount(
                args.accountId, args.env,
                ServerTwoFactorToken(token, TwoFactorProviderType.Fido2WebAuthn, rememberMe.value),
                args.clientSecret, args.email, args.password,
            ).bind()
            currentCoroutineContext().ensureActive()
            phase.value = Fido2LoginPhase.COMPLETE
            transmitter(Unit)
        }

        fun start(block: suspend () -> Unit) {
            if (phase.value == Fido2LoginPhase.COMPLETE || phase.value == Fido2LoginPhase.SUBMITTING) return
            job?.cancel()
            browserRequest = null
            deeplinkService.clear("webauthn-callback")
            deeplinkService.clear("webauthn-error")
            val attempt = ++generation
            phase.value = Fido2LoginPhase.REQUESTING
            job = launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    screen.message(error)
                } finally {
                    if (attempt == generation && phase.value != Fido2LoginPhase.COMPLETE) {
                        phase.value = Fido2LoginPhase.IDLE
                    }
                }
            }.also { it.start() }
        }

        fun native() {
            if (phase.value != Fido2LoginPhase.IDLE) return
            start {
                val request = nextRequest() ?: return@start
                phase.value = Fido2LoginPhase.AUTHENTICATING
                val bytes = prompts.execute(Fido2Operation.Assert(request))
                val token = try {
                    bitwardenFido2Token(request, decodeFido2AssertionResult(bytes))
                } catch (_: Exception) {
                    throw Fido2Exception(Fido2Failure.PROTOCOL).asFido2AppException()
                } finally {
                    bytes.fill(0)
                }
                submit(token)
            }
        }

        fun browser() {
            if (phase.value != Fido2LoginPhase.IDLE && phase.value != Fido2LoginPhase.AUTHENTICATING) return
            start {
                val request = nextRequest() ?: return@start
                val data = buildJsonObject {
                    put("data", request.webAuthnJson())
                    put("callbackUri", callbackUrl)
                    put("headerText", header)
                    put("btnText", button)
                    put("btnReturnText", returnButton)
                }.let { base64Service.encodeToString(json.encodeToString(it)) }
                val url = URLBuilder(Url(vaultUrl)).apply {
                    appendPathSegments("webauthn-mobile-connector.html")
                    parameters.append("data", data)
                    parameters.append("parent", callbackUrl)
                    parameters.append("v", "2")
                }.buildString()
                browserRequest = request
                screen.navigate(NavigationIntent.NavigateToBrowser(url))
            }
        }

        fun complete(result: Either<Throwable, String>) {
            val request = browserRequest ?: return
            result.fold(
                ifLeft = { error ->
                    browserRequest = null
                    screen.message(error)
                },
                ifRight = { token ->
                    // A late callback from a previous browser attempt must not consume
                    // the pending challenge or cancel the current request.
                    val validated = try {
                        bitwardenFido2Token(request, bitwardenFido2BrowserResult(token))
                    } catch (_: Exception) {
                        screen.message(Fido2Exception(Fido2Failure.PROTOCOL).asFido2AppException())
                        return
                    }
                    start { submit(validated) }
                },
            )
        }

        launch {
            deeplinkService.getFlow("webauthn-callback").distinctUntilChanged().collectLatest { token ->
                if (token != null && browserRequest != null) complete(Either.Right(token))
            }
        }
        launch {
            deeplinkService.getFlow("webauthn-error").distinctUntilChanged().collectLatest { error ->
                if (error != null && browserRequest != null) {
                    complete(Either.Left(Fido2Exception(Fido2Failure.REJECTED).asFido2AppException()))
                }
            }
        }
        try {
            combine(phase, rememberMe) { currentPhase, remember ->
                val locked = currentPhase == Fido2LoginPhase.SUBMITTING || currentPhase == Fido2LoginPhase.COMPLETE
                val loading = currentPhase != Fido2LoginPhase.IDLE && currentPhase != Fido2LoginPhase.COMPLETE
                BitwardenLoginTwofaState.Fido2WebAuthn(
                    authUrl = "",
                    callbackUrls = callbackUrls,
                    rememberMe = BitwardenLoginTwofaState.Fido2WebAuthn.RememberMe(
                        checked = remember,
                        onChange = (rememberMe::value::set).takeUnless { locked },
                    ),
                    onBrowser = ::browser.takeIf {
                        browserAvailable && !locked && currentPhase != Fido2LoginPhase.REQUESTING
                    },
                    onComplete = ::complete.takeUnless { locked },
                    nativeAvailable = nativeAvailable,
                    isLoading = loading,
                    prompts = prompts.events,
                    primaryAction = fido2PrimaryAction(
                        nativeAvailable, loading, ::native.takeIf { !loading && !locked },
                    ),
                )
            }.collect { send(it) }
        } finally {
            browserRequest = null
            deeplinkService.clear("webauthn-callback")
            deeplinkService.clear("webauthn-error")
        }
    }.flowOn(Dispatchers.Main.immediate)
}

private suspend fun RememberStateFlowScope.fido2PrimaryAction(
    nativeAvailable: Boolean,
    isLoading: Boolean,
    onClick: (() -> Unit)?,
): BitwardenLoginTwofaState.PrimaryAction? = if (nativeAvailable) {
    BitwardenLoginTwofaState.PrimaryAction(
        text = translate(Res.string.fido2_authenticate_action),
        icon = if (isLoading) {
            BitwardenLoginTwofaState.PrimaryAction.Icon.LOADING
        } else {
            BitwardenLoginTwofaState.PrimaryAction.Icon.LOGIN
        },
        onClick = onClick,
    )
} else {
    null
}

private enum class Fido2LoginPhase {
    IDLE,
    REQUESTING,
    AUTHENTICATING,
    SUBMITTING,
    COMPLETE,
}
