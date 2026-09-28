package com.artemchep.keyguard.apple.auth

import arrow.core.right
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.LoginState
import com.artemchep.keyguard.feature.auth.bitwarden.LoginStateItem
import com.artemchep.keyguard.feature.auth.bitwarden.bitwardenLoginStateProducer
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.TwoFactorState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.bitwardenLoginTwofaStateProducer
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The Bitwarden add-account / login screen and its 2FA challenge. Runs the
 * shared [bitwardenLoginStateProducer] / [bitwardenLoginTwofaStateProducer]
 * headlessly and projects them into [LoginSnapshot] / [TwofaSnapshot]. The 2FA
 * challenge args captured during login are handed off to [observeBitwardenLoginTwofa].
 */
internal class LoginController(
    private val ctx: CoreContext,
) {
    private var latestLoginState: LoginState? = null
    private var loginFieldHandlers: Map<String, (String) -> Unit> = emptyMap()
    private var loginActionHandlers: Map<String, () -> Unit> = emptyMap()
    private var latestTwofaArgs: BitwardenLoginTwofaRoute.Args? = null
    private var latestTwoFactorState: TwoFactorState? = null

    /**
     * The args of the next [observeBitwardenLogin], set when a producer asks for a
     * login (an account's re-login locks its email and server). Taken once, when
     * the observation starts. Main-confined.
     */
    private var pendingLoginArgs: BitwardenLoginRoute.Args? = null

    fun prepareBitwardenLogin(args: BitwardenLoginRoute.Args) {
        pendingLoginArgs = args
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBitwardenLogin(
        onChange: (LoginSnapshot) -> Unit,
        onSuccess: () -> Unit,
        onTwofaRequired: () -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val args = pendingLoginArgs ?: BitwardenLoginRoute.Args()
        pendingLoginArgs = null
        return ctx.launchSessionObserver(
            onLocked = {
                latestLoginState = null
                loginFieldHandlers = emptyMap()
                loginActionHandlers = emptyMap()
                onChange(LoginSnapshot.empty)
            },
        ) { state ->
            // AddAccount lives in the unlocked session sub-DI; the
            // global bindings (url check, confirmation factory) are
            // reachable through it too, since it parents the global DI.
            val addAccount = state.sessionKoin.get<AddAccount>()
            val cipherUnsecureUrlCheck = state.sessionKoin.get<CipherUnsecureUrlCheck>()
            val confirmationRouteFactory = ctx.koin.get<ConfirmationRouteFactory>()
            val producerFlow = ctx.koin.newHeadlessStateFlowScope("bitwardenlogin", this)
                .bitwardenLoginStateProducer(
                    addAccount = addAccount,
                    cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
                    confirmationRouteFactory = confirmationRouteFactory,
                    args = args,
                )
            // Single shared copy of the latest top-level state. The
            // producer is cold and double-collecting it would run the
            // form twice, so fan out from one StateFlow instead.
            val latest = MutableStateFlow<LoginState?>(null)
            launch {
                producerFlow.collect { loadable ->
                    val loginState = loadable.getOrNull()
                    latest.value = loginState
                    ctx.publishOnMain {
                        latestLoginState = loginState
                    }
                }
            }

            // One-shot side effects, mirroring the Compose screen's
            // CollectedEffect handlers.
            launch {
                latest.filterNotNull()
                    .map { it.effects }
                    .distinctUntilChanged()
                    .flatMapLatest { it.onSuccessFlow }
                    .collectOnMain { onSuccess() }
            }
            launch {
                latest.filterNotNull()
                    .map { it.effects }
                    .distinctUntilChanged()
                    .flatMapLatest { it.onErrorFlow }
                    .collect { error ->
                        // The shared model surfaces a 2FA challenge as
                        // an OtpRequired "error": capture its args and
                        // hand off to the dedicated 2FA observation.
                        when (error) {
                            is BitwardenLoginEvent.Error.OtpRequired -> {
                                ctx.publishOnMain {
                                    latestTwofaArgs = error.args
                                    onTwofaRequired()
                                }
                            }
                        }
                    }
            }

            // Snapshot stream. The dynamic Url/HttpHeader items carry
            // their own inner StateFlows that the top-level state does
            // NOT re-emit for, so combine them in: any inner field
            // edit then re-runs the builder and pushes a fresh snapshot.
            latest.filterNotNull()
                .flatMapLatest { login ->
                    val innerFlows = login.items.mapNotNull { item ->
                        when (item) {
                            is LoginStateItem.Url -> item.state.flow
                            is LoginStateItem.HttpHeader -> item.state.flow
                            else -> null
                        }
                    }
                    if (innerFlows.isEmpty()) {
                        // combine() over an empty list never emits.
                        flowOf(login to emptyList())
                    } else {
                        combine(innerFlows) { states -> login to states.toList() }
                    }
                }
                .throttleLatest()
                .collect { (login, innerStates) ->
                    val fieldHandlers = LinkedHashMap<String, (String) -> Unit>()
                    val actionHandlers = LinkedHashMap<String, () -> Unit>()
                    val snapshot = buildLoginSnapshot(login, innerStates, leContext, fieldHandlers, actionHandlers)
                    ctx.publishOnMain {
                        loginFieldHandlers = fieldHandlers
                        loginActionHandlers = actionHandlers
                        onChange(snapshot)
                    }
                }
        }
    }

    private suspend fun buildLoginSnapshot(
        login: LoginState,
        innerStates: List<Any>,
        leContext: LeContext,
        fieldHandlers: LinkedHashMap<String, (String) -> Unit>,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
    ): LoginSnapshot {
        suspend fun List<FlatItemAction>.toSnapshots(itemId: String): List<LoginActionSnapshot> {
            val keys = ActionKeyAllocator(itemId)
            return map { action ->
                val title = textResource(action.title, leContext)
                val actionId = keys.keyFor(action, title)
                action.onClick?.let { actionHandlers[actionId] = it }
                LoginActionSnapshot(
                    id = actionId,
                    title = title,
                )
            }
        }

        val email = login.email.toFieldSnapshot(fieldHandlers)
        val password = login.password.toFieldSnapshot(fieldHandlers)
        val clientSecret = login.clientSecret?.toFieldSnapshot(fieldHandlers)

        val regions = login.regionItems.map { region ->
            LoginRegionSnapshot(
                key = region.key,
                title = textResource(region.title, leContext),
                checked = region.checked,
            )
        }

        // Walk items and the parallel innerStates list together. Only Url and
        // HttpHeader items contribute an inner state, in order.
        var innerIndex = 0
        val items = login.items.map { item ->
            when (item) {
                is LoginStateItem.Url -> {
                    val st = innerStates[innerIndex++] as LoginStateItem.Url.State
                    LoginItemSnapshot(
                        id = item.id,
                        kind = LoginItemKind.URL,
                        field = st.text.toFieldSnapshot(fieldHandlers),
                        keyField = null,
                        label = st.label,
                        text = null,
                        actions = emptyList(),
                    )
                }

                is LoginStateItem.HttpHeader -> {
                    val st = innerStates[innerIndex++] as LoginStateItem.HttpHeader.State
                    LoginItemSnapshot(
                        id = item.id,
                        kind = LoginItemKind.HEADER,
                        field = st.text.toFieldSnapshot(fieldHandlers),
                        keyField = st.label.toFieldSnapshot(fieldHandlers),
                        label = null,
                        text = null,
                        actions = (st.options + item.options).toSnapshots(item.id),
                    )
                }

                is LoginStateItem.Section -> LoginItemSnapshot(
                    id = item.id,
                    kind = LoginItemKind.SECTION,
                    field = null,
                    keyField = null,
                    label = null,
                    text = item.text,
                    actions = emptyList(),
                )

                is LoginStateItem.Label -> LoginItemSnapshot(
                    id = item.id,
                    kind = LoginItemKind.LABEL,
                    field = null,
                    keyField = null,
                    label = null,
                    text = item.text,
                    actions = emptyList(),
                )

                is LoginStateItem.Add -> LoginItemSnapshot(
                    id = item.id,
                    kind = LoginItemKind.ADD,
                    field = null,
                    keyField = null,
                    label = null,
                    text = item.text,
                    actions = item.actions.toSnapshots(item.id),
                )
            }
        }

        return LoginSnapshot(
            email = email,
            password = password,
            clientSecret = clientSecret,
            regions = regions,
            showCustomEnv = login.showCustomEnv,
            items = items,
            isLoading = login.isLoading,
            canLogin = login.onLoginClick != null,
            canRegister = login.onRegisterClick != null,
        )
    }

    /** Writes [text] into a login text field identified by its snapshot id. */
    fun setLoginField(id: String, text: String) {
        loginFieldHandlers[id]?.invoke(text)
    }

    /** Selects a server region (US / EU / Custom) by its snapshot key. */
    fun selectLoginRegion(key: String) {
        latestLoginState?.regionItems
            ?.firstOrNull { it.key == key }
            ?.onClick
            ?.invoke()
    }

    /** Invokes a login item action (header options / add-field button) by its id. */
    fun invokeLoginAction(id: String) {
        loginActionHandlers.invokeAction(id)
    }

    /** Opens the Bitwarden registration page. No-op unless exposed. */
    fun clickLoginRegister() {
        latestLoginState?.onRegisterClick?.invoke()
    }

    /** Submits the Bitwarden login. No-op unless the latest state allows it. */
    fun submitLogin() {
        latestLoginState?.onLoginClick?.invoke()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBitwardenLoginTwofa(
        onChange: (TwofaSnapshot) -> Unit,
        onSuccess: () -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        // [latestTwofaArgs] is main-confined; read it before leaving the caller's
        // main thread (Swift always calls observe* from the main thread).
        val args = latestTwofaArgs
            ?: return KeyguardCancellable(ctx.scope.launch { onChange(TwofaSnapshot.empty) })
        return ctx.launchSessionObserver(
            onLocked = {
                latestTwoFactorState = null
                onChange(TwofaSnapshot.empty)
            },
        ) { state ->
            // AddAccount / RequestEmailTfa live in the unlocked session
            // sub-DI; the crypto / serialization services come from global.
            val addAccount = state.sessionKoin.get<AddAccount>()
            val requestEmailTfa = state.sessionKoin.get<RequestEmailTfa>()
            val cryptoGenerator = ctx.koin.get<CryptoGenerator>()
            val base64Service = ctx.koin.get<Base64Service>()
            val deeplinkService = ctx.koin.get<DeeplinkService>()
            val json = ctx.koin.get<Json>()
            // The shared 2FA producer reports success by invoking its
            // RouteResultTransmitter; bridge that to onSuccess(). The
            // producer fires it from the background pipeline — hop to
            // the main scope like every other Swift-facing callback.
            val transmitter = object : RouteResultTransmitter<Unit> {
                override fun invoke(p1: Unit) {
                    ctx.scope.launch { onSuccess() }
                }
            }
            val producerFlow = ctx.koin.newHeadlessStateFlowScope("bitwardentwofa", this)
                .bitwardenLoginTwofaStateProducer(
                    cryptoGenerator = cryptoGenerator,
                    base64Service = base64Service,
                    deeplinkService = deeplinkService,
                    json = json,
                    addAccount = addAccount,
                    requestEmailTfa = requestEmailTfa,
                    args = args,
                    transmitter = transmitter,
                    defaultRememberMe = false,
                )
            // Single shared copy of the latest top-level state (the
            // producer is cold; double-collecting would run it twice).
            val latest = MutableStateFlow<TwoFactorState?>(null)
            launch {
                producerFlow.collect { twofa -> latest.value = twofa }
            }
            // loadingState is a separate inner StateFlow the top-level
            // state does not re-emit for; combine it so the submit
            // spinner's on/off pushes a fresh snapshot.
            latest.filterNotNull()
                .flatMapLatest { twofa ->
                    twofa.loadingState.map { twofa }
                }
                .throttleLatest()
                .map { twofa -> twofa to buildTwofaSnapshot(twofa, args, leContext) }
                .collectOnMain { (twofa, snapshot) ->
                    latestTwoFactorState = twofa
                    onChange(snapshot)
                }
        }
    }

    private suspend fun buildTwofaSnapshot(
        twofa: TwoFactorState,
        args: BitwardenLoginTwofaRoute.Args,
        leContext: LeContext,
    ): TwofaSnapshot {
        val providers = twofa.providers.map { item ->
            TwofaProviderSnapshot(
                key = item.key,
                title = textResource(item.title, leContext),
                checked = item.checked,
            )
        }
        val isLoading = twofa.loadingState.value

        fun codeField(model: TextFieldModel) = model.toFieldSnapshot(
            id = "twofa.code",
            placeholder = null,
        )

        fun fallback() = TwofaSnapshot(
            providers = providers,
            kind = TwofaKind.FALLBACK,
            code = null,
            emailNote = null,
            canResend = false,
            rememberMe = false,
            rememberMeEnabled = false,
            fallbackTitle = providers.firstOrNull { it.checked }?.title,
            webVaultUrl = args.env.buildWebVaultUrl(),
            primaryActionText = null,
            canSubmit = false,
            isLoading = isLoading,
        )

        return when (val s = twofa.state) {
            is BitwardenLoginTwofaState.Skeleton -> TwofaSnapshot(
                providers = providers,
                kind = TwofaKind.SKELETON,
                code = null,
                emailNote = null,
                canResend = false,
                rememberMe = false,
                rememberMeEnabled = false,
                fallbackTitle = null,
                webVaultUrl = null,
                primaryActionText = null,
                canSubmit = false,
                isLoading = isLoading,
            )

            is BitwardenLoginTwofaState.Authenticator -> TwofaSnapshot(
                providers = providers,
                kind = TwofaKind.AUTHENTICATOR,
                code = codeField(s.code),
                emailNote = null,
                canResend = false,
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                fallbackTitle = null,
                webVaultUrl = null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
                isLoading = isLoading,
            )

            is BitwardenLoginTwofaState.Email -> TwofaSnapshot(
                providers = providers,
                kind = TwofaKind.EMAIL,
                code = codeField(s.code),
                emailNote = s.email,
                canResend = s.emailResend != null,
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                fallbackTitle = null,
                webVaultUrl = null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
                isLoading = isLoading,
            )

            is BitwardenLoginTwofaState.EmailNewDevice -> TwofaSnapshot(
                providers = providers,
                kind = TwofaKind.EMAIL_NEW_DEVICE,
                code = codeField(s.code),
                emailNote = s.email,
                canResend = s.emailResend != null,
                rememberMe = false,
                rememberMeEnabled = false,
                fallbackTitle = null,
                webVaultUrl = null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
                isLoading = isLoading,
            )

            is BitwardenLoginTwofaState.YubiKey -> TwofaSnapshot(
                providers = providers,
                kind = TwofaKind.YUBIKEY,
                code = null,
                emailNote = null,
                canResend = false,
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                fallbackTitle = null,
                webVaultUrl = null,
                primaryActionText = null,
                canSubmit = s.onComplete != null,
                isLoading = isLoading,
            )

            is BitwardenLoginTwofaState.Unsupported -> fallback()
            is BitwardenLoginTwofaState.Duo -> fallback()
            is BitwardenLoginTwofaState.Fido2WebAuthn -> fallback()
        }
    }

    /** Writes [text] into the 2FA verification-code field. */
    fun setTwofaCode(text: String) {
        val onChange = when (val s = latestTwoFactorState?.state) {
            is BitwardenLoginTwofaState.Authenticator -> s.code.onChange
            is BitwardenLoginTwofaState.Email -> s.code.onChange
            is BitwardenLoginTwofaState.EmailNewDevice -> s.code.onChange
            else -> null
        }
        onChange?.invoke(text)
    }

    /** Selects a 2FA provider by its snapshot key. */
    fun selectTwofaProvider(key: String) {
        latestTwoFactorState?.providers
            ?.firstOrNull { it.key == key }
            ?.onClick
            ?.invoke()
    }

    /** Toggles "remember this device" for providers that support it. */
    fun toggleTwofaRememberMe(checked: Boolean) {
        val onChange = when (val s = latestTwoFactorState?.state) {
            is BitwardenLoginTwofaState.Authenticator -> s.rememberMe.onChange
            is BitwardenLoginTwofaState.Email -> s.rememberMe.onChange
            is BitwardenLoginTwofaState.YubiKey -> s.rememberMe.onChange
            else -> null
        }
        onChange?.invoke(checked)
    }

    /** Requests a fresh verification email (email / email-new-device providers). */
    fun resendTwofaCode() {
        val resend = when (val s = latestTwoFactorState?.state) {
            is BitwardenLoginTwofaState.Email -> s.emailResend
            is BitwardenLoginTwofaState.EmailNewDevice -> s.emailResend
            else -> null
        }
        resend?.invoke()
    }

    /** Submits the code-entry 2FA challenge. No-op unless the state allows it. */
    fun submitTwofa() {
        val onClick = (latestTwoFactorState?.state as? BitwardenLoginTwofaState.HasPrimaryAction)
            ?.primaryAction
            ?.onClick
        onClick?.invoke()
    }

    /** Completes a YubiKey OTP challenge with the manually-typed [token]. */
    fun submitTwofaYubiKey(token: String) {
        val s = latestTwoFactorState?.state as? BitwardenLoginTwofaState.YubiKey
        s?.onComplete?.invoke(token.right())
    }
}
