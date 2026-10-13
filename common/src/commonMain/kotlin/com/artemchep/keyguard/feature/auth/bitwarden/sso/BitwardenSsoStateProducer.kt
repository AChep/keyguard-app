package com.artemchep.keyguard.feature.auth.bitwarden.sso

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginContinuation
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRouteFactory
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.toModel
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.BitwardenSsoService
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SsoAuthenticated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.koin.compose.currentKoinScope

@Composable
fun produceBitwardenSsoScreenState(
    args: BitwardenSsoRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
): BitwardenSsoState = with(currentKoinScope()) {
    produceBitwardenSsoScreenState(
        service = get(),
        twofaRouteFactory = get(),
        args = args,
        transmitter = transmitter,
    )
}

@Composable
fun produceBitwardenSsoScreenState(
    service: BitwardenSsoService,
    twofaRouteFactory: BitwardenLoginTwofaRouteFactory,
    args: BitwardenSsoRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
): BitwardenSsoState = produceScreenState(
    key = "bitwarden_sso",
    initial = BitwardenSsoState(
        isLoading = true,
    ),
    args = arrayOf(
        service,
        args,
        transmitter,
    ),
) {
    bitwardenSsoStateProducer(
        service = service,
        twofaRouteFactory = twofaRouteFactory,
        args = args,
        transmitter = transmitter,
    )
}

suspend fun RememberStateFlowScope.bitwardenSsoStateProducer(
    service: BitwardenSsoService,
    twofaRouteFactory: BitwardenLoginTwofaRouteFactory,
    args: BitwardenSsoRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
): Flow<BitwardenSsoState> {
    // A restored screen waits for the user instead
    // of opening the browser again.
    val started = mutablePersistedFlow("started") { false }
    val autoStart = !started.value
    started.value = true
    return bitwardenSsoStateFlow(
        service = service,
        args = args,
        autoStart = autoStart,
        onTwoFactor = { twofaArgs ->
            // The 2FA screen only authenticates; the vault
            // unlock continues on this screen.
            val route = registerRouteResultReceiver(twofaRouteFactory.create(twofaArgs)) {
                navigate(NavigationIntent.Pop)
            }
            navigate(NavigationIntent.NavigateToRoute(route))
        },
        onSuccess = {
            transmitter(Unit)
        },
    )
}

private sealed interface SsoStep {
    /** Waits for the user: after a restore, a failure, a closed browser, or while the 2FA screen is open. */
    data object Idle : SsoStep

    data class Browser(
        val prompt: SsoBrowserPrompt,
    ) : SsoStep

    /** Exchanges the code, or unlocks with Key Connector, which cannot be retried. */
    data class Loading(
        val canRetry: Boolean,
    ) : SsoStep

    /** Waits for the master password; [onSubmit] accepts one password, `null` while unlocking. */
    data class Password(
        val onSubmit: ((String) -> Unit)?,
    ) : SsoStep
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun RememberStateFlowScope.bitwardenSsoStateFlow(
    service: BitwardenSsoService,
    args: BitwardenSsoRoute.Args,
    autoStart: Boolean,
    onTwoFactor: (BitwardenLoginTwofaRoute.Args) -> Unit,
    onSuccess: () -> Unit,
): Flow<BitwardenSsoState> {
    val password = TextFieldHandle(MutableStateFlow(TextCell(text = "")), "sso.password")
    screenScope.coroutineContext.job.invokeOnCompletion {
        password.setText("")
    }

    fun newPrompt() = SsoBrowserPrompt(
        request = service.request(args.env, args.email, args.organizationIdentifier),
    )

    // A new prompt starts a new attempt
    // and cancels the running one.
    val promptSink = MutableStateFlow(newPrompt().takeIf { autoStart })
    val onRetry = {
        promptSink.value = newPrompt()
    }
    val stepFlow = promptSink
        .flatMapLatest { prompt ->
            prompt
                ?.let {
                    ssoAttemptFlow(
                        service = service,
                        args = args,
                        prompt = it,
                        onTwoFactor = onTwoFactor,
                        onSuccess = onSuccess,
                    )
                }
                ?: flowOf(SsoStep.Idle)
        }
        // The attempt must keep running without subscribers,
        // e.g. while the 2FA screen is on top.
        .stateIn(
            scope = screenScope,
            started = SharingStarted.Eagerly,
            initialValue = promptSink.value?.let(SsoStep::Browser) ?: SsoStep.Idle,
        )
    return combine(stepFlow, password.sink) { step, _ ->
        step.toState(
            password = password,
            onRetry = onRetry,
        )
    }
}

private fun SsoStep.toState(
    password: TextFieldHandle,
    onRetry: () -> Unit,
): BitwardenSsoState = when (this) {
    SsoStep.Idle -> BitwardenSsoState(
        onRetry = onRetry,
    )

    is SsoStep.Browser -> BitwardenSsoState(
        browser = prompt,
        isLoading = true,
        onRetry = onRetry,
    )

    is SsoStep.Loading -> BitwardenSsoState(
        isLoading = true,
        onRetry = onRetry.takeIf { canRetry },
    )

    is SsoStep.Password -> BitwardenSsoState(
        password = password.toModel(),
        isLoading = onSubmit == null,
        onUnlock = onSubmit
            ?.takeIf { password.sink.value.text.isNotEmpty() }
            ?.let { submit ->
                {
                    val value = password.sink.value.text
                    password.setText("")
                    submit(value)
                }
            },
    )
}

/**
 * Signs in with the browser [prompt], then unlocks the vault. Disposed
 * with its screen, including when the vault locks.
 */
private fun RememberStateFlowScope.ssoAttemptFlow(
    service: BitwardenSsoService,
    args: BitwardenSsoRoute.Args,
    prompt: SsoBrowserPrompt,
    onTwoFactor: (BitwardenLoginTwofaRoute.Args) -> Unit,
    onSuccess: () -> Unit,
): Flow<SsoStep> = flow {
    emit(SsoStep.Browser(prompt))
    val authorization = prompt.awaitAuthorization()
    if (authorization == null) {
        // The user has closed the browser.
        emit(SsoStep.Idle)
        return@flow
    }

    // Until the browser sign-in succeeds, the attempt may wait for a closed
    // browser tab or a 2FA screen that never reports back; a retry replaces it.
    emit(SsoStep.Loading(canRetry = true))
    val authenticated = try {
        service.authenticate(authorization, null).bind()
    } catch (error: ApiException) {
        val challenge = error.type as? ApiException.Type.TwoFaRequired ?: throw error
        emit(SsoStep.Idle)
        val authenticated = awaitTwoFactor(
            service = service,
            args = args,
            authorization = authorization,
            challenge = challenge,
            onTwoFactor = onTwoFactor,
        )
        if (authenticated == null) {
            // Leaving 2FA without authenticating ends
            // the attempt; the code is single-use.
            emit(SsoStep.Idle)
            return@flow
        }
        authenticated
    }

    if (authenticated.keyConnectorUrl != null) {
        // A Key Connector account has no password step.
        emit(SsoStep.Loading(canRetry = false))
        service.unlockWithKeyConnector(args.accountId, authenticated).bindIn(appScope)
    } else {
        do {
            val submission = CompletableDeferred<String>()
            emit(SsoStep.Password(onSubmit = { submission.complete(it) }))
            val value = submission.await()
            emit(SsoStep.Password(onSubmit = null))
            val result = service.unlock(args.accountId, authenticated, value)
                .attempt()
                .bindIn(appScope)
            // A failure keeps the password step.
            result.onLeft { message(it) }
        } while (result.isLeft())
    }
    onSuccess()
}
    // Surface browser, network, and protocol failures; a failure
    // before the password step offers a retry.
    .catch { error ->
        message(error)
        emit(SsoStep.Idle)
    }
    .onCompletion {
        prompt.cancel()
    }

/** Waits for the browser sign-in; `null` if the user has closed the browser. */
private suspend fun SsoBrowserPrompt.awaitAuthorization(): SsoAuthorization? = try {
    withTimeout(SsoRequest.TIMEOUT_MS) {
        result.await()
    }
} catch (_: TimeoutCancellationException) {
    throw SsoException(SsoFailure.AUTHENTICATION)
} catch (_: CancellationException) {
    // Only the prompt is cancelled, not the attempt.
    currentCoroutineContext().ensureActive()
    null
}

/**
 * Hands the [challenge] to the 2FA screen and waits until it authenticates;
 * `null` if the 2FA screen closes without authenticating. A Key Connector
 * account also waits for the 2FA screen to close.
 */
private suspend fun awaitTwoFactor(
    service: BitwardenSsoService,
    args: BitwardenSsoRoute.Args,
    authorization: SsoAuthorization,
    challenge: ApiException.Type.TwoFaRequired,
    onTwoFactor: (BitwardenLoginTwofaRoute.Args) -> Unit,
): SsoAuthenticated? {
    val attempt = currentCoroutineContext().job
    val authenticatedDeferred = CompletableDeferred<SsoAuthenticated?>()
    val dismissedDeferred = CompletableDeferred<Unit>()
    // The latest challenge has the email and the
    // session token to resend the email code with.
    val challengeSink = MutableStateFlow(challenge)

    /**
     * A replaced or closed attempt fails as a sign-in, not as a cancellation:
     * the 2FA screen is not cancelled and must show the error.
     */
    fun requireActive() {
        if (!attempt.isActive) throw SsoException(SsoFailure.AUTHENTICATION)
    }

    val continuation = object : BitwardenLoginContinuation {
        override fun authenticate(token: ServerTwoFactorToken?): IO<Unit> = ioEffect {
            requireActive()
            val authenticated = try {
                service.authenticate(authorization, token).bind()
            } catch (error: ApiException) {
                val next = error.type as? ApiException.Type.TwoFaRequired
                if (next != null) {
                    challengeSink.update { old ->
                        next.copy(
                            ssoEmailSessionToken = next.ssoEmailSessionToken ?: old.ssoEmailSessionToken,
                            email = next.email ?: old.email,
                        )
                    }
                }
                throw error
            }
            requireActive()
            authenticatedDeferred.complete(authenticated)
        }

        override fun onDismiss() {
            authenticatedDeferred.complete(null)
            dismissedDeferred.complete(Unit)
        }

        override fun resendEmail(): IO<Unit> = ioEffect {
            requireActive()
            val latest = challengeSink.value
            service.resendEmail(
                authorization,
                latest.email ?: authorization.request.email,
                latest.ssoEmailSessionToken,
            ).bind()
        }
    }
    onTwoFactor(
        BitwardenLoginTwofaRoute.Args(
            providers = challenge.providers,
            email = challenge.email ?: authorization.request.email,
            env = args.env,
            continuation = continuation,
        ),
    )
    val authenticated = authenticatedDeferred.await()
        ?: return null
    // A Key Connector account unlocks once the 2FA screen closes.
    if (authenticated.keyConnectorUrl != null) {
        dismissedDeferred.await()
    }
    return authenticated
}

/**
 * Saves the account in the [scope], like the password login does:
 * leaving the screen mid-save must not skip the sync and backup after it.
 */
private suspend fun <T> IO<T>.bindIn(scope: CoroutineScope): T = scope.async { bind() }.await()
