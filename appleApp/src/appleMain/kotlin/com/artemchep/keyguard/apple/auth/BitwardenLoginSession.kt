package com.artemchep.keyguard.apple.auth

import arrow.core.right
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.LoginServerDiscovery
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
import com.artemchep.keyguard.provider.bitwarden.usecase.DiscoverBitwardenServer
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import org.koin.core.scope.Scope

/** A login and its 2FA challenge belong to one presentation. Main-confined. */
class BitwardenLoginSession internal constructor(
    private val ctx: CoreContext,
    private val fido2PromptHost: Fido2PromptController,
    private var args: BitwardenLoginRoute.Args?,
    val dialogs: com.artemchep.keyguard.apple.dialog.FormDialogsSession,
    private val onDispose: () -> Unit,
) {
    private val form = DetailSession<LoginSnapshot, LoginActions>(onDispose = ::dispose)
    private var twofa: DetailSession<TwofaSnapshot, TwoFactorState?>? = null
    private var latestTwofaArgs: BitwardenLoginTwofaRoute.Args? = null
    private var complete: (() -> Unit)? = null
    internal var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean) = { { false } }

    fun observe(
        onChange: (LoginSnapshot) -> Unit,
        onClose: () -> Unit,
        onTwofaRequired: () -> Unit,
    ): KeyguardCancellable = form.observe(onChange, onClose) { publish, finish ->
        complete = finish
        val onOtpRequired = form.gated<BitwardenLoginTwofaRoute.Args> { twofaArgs ->
            stopTwofa()
            latestTwofaArgs = twofaArgs
            onTwofaRequired()
        }
        observeLogin(publish, finish, onOtpRequired)
    }

    fun close() = form.close()

    private fun dispose() {
        args = null
        stopTwofa()
        complete = null
        dialogs.close()
        onDispose()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLogin(
        publish: (LoginSnapshot, LoginActions) -> Unit,
        onSuccess: () -> Unit,
        onOtpRequired: (BitwardenLoginTwofaRoute.Args) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val args = args ?: BitwardenLoginRoute.Args()
        return ctx.launchSessionObserver(onLocked = onSuccess) { state ->
            // AddAccount is session-scoped; the session scope also
            // resolves global bindings such as the url check.
            val addAccount = state.sessionKoin.get<AddAccount>()
            val cipherUnsecureUrlCheck = state.sessionKoin.get<CipherUnsecureUrlCheck>()
            val confirmationRouteFactory = ctx.koin.get<ConfirmationRouteFactory>()
            val discoverBitwardenServer = ctx.koin.get<DiscoverBitwardenServer>()
            val producerFlow = ctx.koin.newHeadlessStateFlowScope(
                "bitwardenlogin", this, navigationInterceptorProvider(state.sessionKoin),
            )
                .bitwardenLoginStateProducer(
                    addAccount = addAccount,
                    cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
                    confirmationRouteFactory = confirmationRouteFactory,
                    discoverBitwardenServer = discoverBitwardenServer,
                    args = args,
                )
            // Single shared copy of the latest top-level state. The
            // producer is cold and double-collecting it would run the
            // form twice, so fan out from one StateFlow instead.
            val latest = MutableStateFlow<LoginState?>(null)
            launch {
                producerFlow.collect { loadable -> latest.value = loadable.getOrNull() }
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
                                ctx.publishOnMain { onOtpRequired(error.args) }
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
                .map { (login, innerStates) ->
                    val actions = LoginActions(login)
                    buildLoginSnapshot(login, innerStates, leContext, actions.fields, actions.actions) to actions
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
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
            serverDiscovery = login.serverDiscovery?.toSnapshot(),
        )
    }

    fun setLoginField(id: String, text: String) = form.withActions { it.fields[id]?.invoke(text) }

    fun selectLoginRegion(key: String) = form.withActions { actions ->
        actions.state.regionItems.firstOrNull { it.key == key }?.onClick?.invoke()
    }

    fun invokeLoginAction(id: String) = form.withActions { it.actions.invokeAction(id) }

    fun clickLoginRegister() = form.withActions { it.state.onRegisterClick?.invoke() }

    fun submitLogin() = form.withActions { it.state.onLoginClick?.invoke() }

    fun discoverLoginServer() = form.withActions { it.state.serverDiscovery?.onClick?.invoke() }

    fun observeTwofa(onChange: (TwofaSnapshot) -> Unit): KeyguardCancellable {
        val args = latestTwofaArgs ?: return KeyguardCancellable {}
        twofa?.close()
        val child = DetailSession<TwofaSnapshot, TwoFactorState?>()
        twofa = child
        return child.observe(onChange) { publish ->
            observeTwofaSource(args, publish, onSuccess = child.gated<Unit> { complete?.invoke() })
        }
    }

    fun stopTwofa() {
        twofa?.close()
        twofa = null
        latestTwofaArgs = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTwofaSource(
        args: BitwardenLoginTwofaRoute.Args,
        publish: (TwofaSnapshot, TwoFactorState?) -> Unit,
        onSuccess: (Unit) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = { publish(TwofaSnapshot.empty, null) },
        ) { state ->
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
                    ctx.scope.launch { onSuccess(Unit) }
                }
            }
            val producerFlow = ctx.koin.newHeadlessStateFlowScope(
                "bitwardentwofa", this, navigationInterceptorProvider(state.sessionKoin),
            )
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
            // The producer is cold, so collect it once into a shared StateFlow.
            val latest = MutableStateFlow<TwoFactorState?>(null)
            launch {
                producerFlow.collect { twofa -> latest.value = twofa }
            }
            launch {
                latest.filterNotNull()
                    .map { it.state as? BitwardenLoginTwofaState.Fido2WebAuthn }
                    .map { it?.prompts ?: emptyFlow() }
                    .distinctUntilChanged()
                    .flatMapLatest { it }
                    .collectLatest { prompt -> fido2PromptHost.handle(prompt) }
            }
            // loadingState is a separate inner StateFlow the top-level
            // state does not re-emit for; combine it so the submit
            // spinner's on/off pushes a fresh snapshot.
            latest.filterNotNull()
                .flatMapLatest { twofa ->
                    twofa.loadingState.map { twofa }
                }
                .throttleLatest()
                .map { twofa -> buildTwofaSnapshot(twofa, args, leContext) to twofa }
                .collectOnMain { (snapshot, twofa) -> publish(snapshot, twofa) }
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

        // Each kind starts from the empty snapshot and sets only the fields it shows.
        val base = TwofaSnapshot.empty.copy(
            providers = providers,
            isLoading = isLoading,
        )

        fun fallback() = base.copy(
            kind = TwofaKind.FALLBACK,
            fallbackTitle = providers.firstOrNull { it.checked }?.title,
            webVaultUrl = args.env.buildWebVaultUrl(),
        )

        return when (val s = twofa.state) {
            is BitwardenLoginTwofaState.Skeleton -> base.copy(
                kind = TwofaKind.SKELETON,
            )

            is BitwardenLoginTwofaState.Authenticator -> base.copy(
                kind = TwofaKind.AUTHENTICATOR,
                code = codeField(s.code),
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
            )

            is BitwardenLoginTwofaState.Email -> base.copy(
                kind = TwofaKind.EMAIL,
                code = codeField(s.code),
                emailNote = s.email,
                canResend = s.emailResend != null,
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
            )

            is BitwardenLoginTwofaState.EmailNewDevice -> base.copy(
                kind = TwofaKind.EMAIL_NEW_DEVICE,
                code = codeField(s.code),
                emailNote = s.email,
                canResend = s.emailResend != null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
            )

            is BitwardenLoginTwofaState.YubiKey -> base.copy(
                kind = TwofaKind.YUBIKEY,
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                canSubmit = s.onComplete != null,
            )

            is BitwardenLoginTwofaState.Unsupported -> fallback()
            is BitwardenLoginTwofaState.Duo -> fallback()
            is BitwardenLoginTwofaState.Fido2WebAuthn -> if (s.nativeAvailable) base.copy(
                kind = TwofaKind.FIDO2,
                rememberMe = s.rememberMe.checked,
                rememberMeEnabled = s.rememberMe.onChange != null,
                primaryActionText = s.primaryAction?.text,
                canSubmit = s.primaryAction?.onClick != null,
                isLoading = s.isLoading,
            ) else fallback()
        }
    }

    private fun withTwofa(block: (BitwardenLoginTwofaState) -> Unit) {
        twofa?.withActions { state -> state?.state?.let(block) }
    }

    fun setTwofaCode(text: String) = withTwofa { s ->
        val onChange = when (s) {
            is BitwardenLoginTwofaState.Authenticator -> s.code.onChange
            is BitwardenLoginTwofaState.Email -> s.code.onChange
            is BitwardenLoginTwofaState.EmailNewDevice -> s.code.onChange
            else -> null
        }
        onChange?.invoke(text)
    }

    fun selectTwofaProvider(key: String) {
        twofa?.withActions { state -> state?.providers?.firstOrNull { it.key == key }?.onClick?.invoke() }
    }

    fun toggleTwofaRememberMe(checked: Boolean) = withTwofa { s ->
        val onChange = when (s) {
            is BitwardenLoginTwofaState.Authenticator -> s.rememberMe.onChange
            is BitwardenLoginTwofaState.Email -> s.rememberMe.onChange
            is BitwardenLoginTwofaState.YubiKey -> s.rememberMe.onChange
            is BitwardenLoginTwofaState.Fido2WebAuthn -> s.rememberMe.onChange
            else -> null
        }
        onChange?.invoke(checked)
    }

    fun resendTwofaCode() = withTwofa { s ->
        val resend = when (s) {
            is BitwardenLoginTwofaState.Email -> s.emailResend
            is BitwardenLoginTwofaState.EmailNewDevice -> s.emailResend
            else -> null
        }
        resend?.invoke()
    }

    fun submitTwofa() = withTwofa { s ->
        (s as? BitwardenLoginTwofaState.HasPrimaryAction)?.primaryAction?.onClick?.invoke()
    }

    fun submitTwofaYubiKey(token: String) = withTwofa { s ->
        (s as? BitwardenLoginTwofaState.YubiKey)?.onComplete?.invoke(token.right())
    }
}

/** The login form's callbacks, published atomically with the snapshot built from the same [state]. */
private class LoginActions(val state: LoginState) {
    val fields = LinkedHashMap<String, (String) -> Unit>()
    val actions = LinkedHashMap<String, () -> Unit>()
}

private fun LoginServerDiscovery.toSnapshot() = LoginServerDiscoverySnapshot(
    isLoading = isLoading,
    enabled = onClick != null,
)
