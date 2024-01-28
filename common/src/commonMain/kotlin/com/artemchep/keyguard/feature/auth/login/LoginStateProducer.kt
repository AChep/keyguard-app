package com.artemchep.keyguard.feature.auth.login

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.runtime.Composable
import arrow.core.partially1
import arrow.core.partially2
import arrow.core.widen
import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.REGEX_US_ASCII
import com.artemchep.keyguard.feature.auth.common.util.ValidationUrl
import com.artemchep.keyguard.feature.auth.common.util.format
import com.artemchep.keyguard.feature.auth.common.util.validateUrl
import com.artemchep.keyguard.feature.auth.common.util.validatedEmail
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.auth.login.otp.LoginTwofaRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.Serializable
import java.util.UUID

private const val TAG = "login"

private const val KEY_EMAIL = "$TAG.email"
private const val KEY_PASSWORD = "$TAG.password"
private const val KEY_REGION = "$TAG.region"

private const val KEY_CLIENT_SECRET = "$TAG.client_secret"

private const val KEY_FAILED_IDENTITY_REQUEST_FINGERPRINT =
    "$TAG.failed_identity_request_fingerprint"

private const val KEY_SERVER_REGION_SECTION = "$TAG.server.region.section"
private const val KEY_SERVER_REGION = "$TAG.server.region"

private const val KEY_SERVER_BASE_SECTION = "$TAG.server.base.section"
private const val KEY_SERVER_BASE_URL = "$TAG.server.base.url"
private const val KEY_SERVER_BASE_URL_DESCRIPTION = "$TAG.server.base.description"

private const val KEY_SERVER_CUSTOM_SECTION = "$TAG.server.custom.section"
private const val KEY_SERVER_CUSTOM_WEB_VAULT_URL = "$TAG.server.custom.web_vault_url"
private const val KEY_SERVER_CUSTOM_API_URL = "$TAG.server.custom.api_url"
private const val KEY_SERVER_CUSTOM_IDENTITY_URL = "$TAG.server.custom.identity_url"
private const val KEY_SERVER_CUSTOM_ICONS_URL = "$TAG.server.custom.icons_url"
private const val KEY_SERVER_CUSTOM_DESCRIPTION = "$TAG.server.custom.description"

@LeParcelize
data class IdentityRequestFingerprint(
    val email: String,
    val password: String,
    // env
    val baseUrl: String = "",
    val identityUrl: String = "",
) : LeParcelable

private data class IdentityCredentials(
    val email: Validated<String>,
    val password: Validated<String>,
    val clientSecret: Validated<String>?,
)

@Composable
fun produceLoginScreenState(
    args: LoginRoute.Args,
): Loadable<LoginState> = with(localDI().direct) {
    produceLoginScreenState(
        addAccount = instance(),
        cipherUnsecureUrlCheck = instance(),
        args = args,
    )
}

private inline val defaultEmail get() = ""

private inline val defaultPassword get() = ""

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun produceLoginScreenState(
    addAccount: AddAccount,
    cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    args: LoginRoute.Args,
): Loadable<LoginState> = produceScreenState(
    initial = Loadable.Loading,
    key = "login",
    args = arrayOf(
        addAccount,
        args,
    ),
) {
    val onSuccessFlow = EventFlow<Unit>()
    val onErrorFlow = EventFlow<LoginEvent.Error>()

    val actionExecutor = screenExecutor()

    // email

    val emailSink = mutablePersistedFlow(KEY_EMAIL) {
        args.email // fallback to default email
            ?: defaultEmail
    }
    val emailState = mutableComposeState(emailSink)

    // A flow of a email + email validation error, if any.
    val validatedEmailFlow = emailSink.filterNotNull()
        .validatedEmail(this)

    // password

    val passwordSink = mutablePersistedFlow(KEY_PASSWORD) {
        args.password // fallback to default password
            ?: defaultPassword
    }
    val passwordState = mutableComposeState(passwordSink)

    // A flow of a password + password validation error, if any.
    val validatedPasswordFlow = passwordSink.filterNotNull()
        .validatedPassword(this)

    // client secret

    val clientSecretSink = mutablePersistedFlow(KEY_CLIENT_SECRET) {
        args.clientSecret // fallback to default client secret
            ?: ""
    }
    val clientSecretState = mutableComposeState(clientSecretSink)

    val failedIdentityRequestFingerprintSink =
        mutablePersistedFlow<IdentityRequestFingerprint?>(KEY_FAILED_IDENTITY_REQUEST_FINGERPRINT) {
            null
        }

    // region

    val regionDefault = if (
        args.env.baseUrl.isNotBlank() ||
        args.env.webVaultUrl.isNotBlank() ||
        args.env.apiUrl.isNotBlank() ||
        args.env.identityUrl.isNotBlank() ||
        args.env.iconsUrl.isNotBlank() ||
        args.env.notificationsUrl.isNotBlank() ||
        args.env.headers.isNotEmpty()
    ) {
        LoginRegion.Custom
    } else {
        LoginRegion.Predefined(args.env.region)
    }
    val regionSink = mutablePersistedFlow(KEY_REGION) { regionDefault.key }
    val regionFlow = regionSink
        .map { regionName ->
            LoginRegion.ofOrNull(regionName)
                ?: regionDefault
        }

    val regionItems = if (args.envEditable) {
        LoginRegion.values
            .asSequence()
            .map { region ->
                FlatItemAction(
                    id = region.key,
                    title = translate(region.title),
                    onClick = {
                        regionSink.value = region.key
                    },
                )
            }
            .toPersistentList()
    } else {
        persistentListOf()
    }

    //
    //
    //

    val envReadOnlyFlow = actionExecutor.isExecutingFlow
        .map { executing ->
            executing || !args.envEditable
        }
        .distinctUntilChanged()

    suspend fun createEnvUrlItem(
        key: String,
        label: String,
        initialValue: () -> String,
        populator: CreateRequest.(String) -> CreateRequest,
    ) = kotlin.run {
        val textSink = mutablePersistedFlow("$key.text") {
            initialValue()
        }
        val textMutableState = mutableComposeState(textSink)
        val textFlow = textSink
            .map { text ->
                val validationUrl = validateUrl(
                    url = text,
                    allowBlank = true,
                )
                val badge = kotlin.run {
                    val isUnsecure =
                        validationUrl == ValidationUrl.OK && cipherUnsecureUrlCheck(text)
                    if (isUnsecure) {
                        return@run TextFieldModel2.Vl(
                            type = TextFieldModel2.Vl.Type.WARNING,
                            text = translate(Res.strings.uri_unsecure),
                        )
                    }

                    null
                }

                TextFieldModel2(
                    text = text,
                    error = validationUrl.format(this),
                    state = textMutableState,
                    vl = badge,
                    onChange = textMutableState::value::set,
                )
            }
            .readOnly(envReadOnlyFlow)

        LoginStateItem.Url(
            id = key,
            state = LocalStateItem(
                flow = textFlow
                    .map { textField ->
                        LoginStateItem.Url.State(
                            label = label,
                            text = textField,
                        )
                    }
                    .persistingStateIn(screenScope, SharingStarted.Lazily),
                populator = { state ->
                    val error = state.text.error != null
                    if (error) {
                        return@LocalStateItem copy(error = true)
                    }

                    val value = state.text.text
                    populator(value)
                },
            ),
        )
    }

    val globalItems = listOf(
        LoginStateItem.Section(
            id = KEY_SERVER_REGION_SECTION,
            text = translate(Res.strings.addaccount_region_section),
        ),
        LoginStateItem.Dropdown(
            id = KEY_SERVER_REGION,
            state = LocalStateItem(
                flow = regionFlow
                    .map { region ->
                        LoginStateItem.Dropdown.State(
                            value = region,
                            title = translate(region.title),
                            text = region.text,
                            options = regionItems,
                        )
                    }
                    .persistingStateIn(screenScope, SharingStarted.Lazily),
                populator = { state ->
                    copy(
                        region = state.value,
                    )
                },
            ),
        ),
    )

    val envServerBaseUrlItem =
        createEnvUrlItem(
            key = KEY_SERVER_BASE_URL,
            label = translate(Res.strings.addaccount_base_env_server_url_label),
            initialValue = { args.env.baseUrl },
        ) { copy(baseUrl = it) }
    val envServerCustomIdentityUrlItem =
        createEnvUrlItem(
            key = KEY_SERVER_CUSTOM_IDENTITY_URL,
            label = translate(Res.strings.addaccount_custom_env_identity_url_label),
            initialValue = { args.env.identityUrl },
        ) { copy(identityUrl = it) }

    val envServerItems = listOf(
        // general
        envServerBaseUrlItem,
        LoginStateItem.Label(
            id = KEY_SERVER_BASE_URL_DESCRIPTION,
            text = translate(Res.strings.addaccount_base_env_note),
        ),
        // custom
        LoginStateItem.Section(
            id = KEY_SERVER_CUSTOM_SECTION,
            text = translate(Res.strings.addaccount_custom_env_section),
        ),
        createEnvUrlItem(
            key = KEY_SERVER_CUSTOM_WEB_VAULT_URL,
            label = translate(Res.strings.addaccount_custom_env_web_vault_url_label),
            initialValue = { args.env.webVaultUrl },
        ) { copy(webVaultUrl = it) },
        createEnvUrlItem(
            key = KEY_SERVER_CUSTOM_API_URL,
            label = translate(Res.strings.addaccount_custom_env_api_url_label),
            initialValue = { args.env.apiUrl },
        ) { copy(apiUrl = it) },
        envServerCustomIdentityUrlItem,
        createEnvUrlItem(
            key = KEY_SERVER_CUSTOM_ICONS_URL,
            label = translate(Res.strings.addaccount_custom_env_icons_url_label),
            initialValue = { args.env.iconsUrl },
        ) { copy(iconsUrl = it) },
        LoginStateItem.Label(
            id = KEY_SERVER_CUSTOM_DESCRIPTION,
            text = translate(Res.strings.addaccount_custom_env_note),
        ),
    )

    val clientSecretRequiredFlow = failedIdentityRequestFingerprintSink
        .flatMapLatest { identityOrNull ->
            // If we don't have a failed request, then do not
            // require a user to fill in the client secret.
            identityOrNull ?: return@flatMapLatest flowOf(false)

            data class IdentityServerData(
                val baseServerUrl: String? = null,
                val identityServerUrl: String? = null,
            )

            // If the identity server is not specified then it is
            // derived from the base server.
            fun of(
                baseServerUrl: String,
                identityServerUrl: String,
            ): IdentityServerData {
                if (identityServerUrl.isNotBlank()) {
                    return IdentityServerData(identityServerUrl = identityServerUrl)
                }
                return IdentityServerData(baseServerUrl = baseServerUrl)
            }

            val identityData = of(
                baseServerUrl = identityOrNull.baseUrl,
                identityServerUrl = identityOrNull.identityUrl,
            )
            val dataFlow = combine(
                envServerBaseUrlItem.state.flow.map { it.text.text },
                envServerCustomIdentityUrlItem.state.flow.map { it.text.text },
            ) { baseServerUrl, identityServerUrl ->
                of(
                    baseServerUrl = baseServerUrl,
                    identityServerUrl = identityServerUrl,
                )
            }
            combine(
                emailSink,
                passwordSink,
                dataFlow,
            ) { email, password, data ->
                identityOrNull.email == email &&
                        identityOrNull.password == password &&
                        identityData == data
            }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val validatedClientSecretFlow = combine(
        clientSecretSink,
        clientSecretRequiredFlow,
    ) { clientSecret, clientSecretRequired ->
        val error = translate(Res.strings.error_must_not_be_blank)
            .takeIf { clientSecretRequired && clientSecret.isBlank() }
        if (error != null) {
            Validated.Failure(clientSecret, error)
        } else {
            Validated.Success(clientSecret)
        }
    }

    val clientCredentialFlow = combine(
        validatedEmailFlow,
        validatedPasswordFlow,
        // Hide client secret field if it is
        // not required.
        combine(
            validatedClientSecretFlow,
            clientSecretRequiredFlow,
        ) { clientSecret, required ->
            clientSecret.takeIf { required }
        },
    ) {
            email,
            password,
            clientSecret,
        ->
        IdentityCredentials(
            email,
            password,
            clientSecret,
        )
    }.shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val envHeadersFactories = kotlin.run {
        val headerFactory = AddStateItemFieldFactory(
            readOnlyFlow = envReadOnlyFlow,
        )
        listOf(
            headerFactory,
        )
    }
    val envHeadersTypesFlow = flowOf(
        listOf(
            Foo2Type(
                type = "header",
                name = translate(Res.strings.addaccount_http_header_type),
            ),
        ),
    )
    val envHeadersFlow = foo3(
        scope = "header",
        initial = args.env.headers,
        initialType = { "header" },
        factories = envHeadersFactories,
        afterList = {
            val header = LoginStateItem.Section(
                id = "header.section",
                text = translate(Res.strings.addaccount_http_header_section),
            )
            add(0, header)
        },
        extra = {
            typeBasedAddItem(
                scope = "header",
                addText = translate(Res.strings.addaccount_http_header_add_more_button),
                typesFlow = envHeadersTypesFlow,
            ).map { items ->
                val info = LoginStateItem.Label(
                    id = "header.info",
                    text = translate(Res.strings.addaccount_http_header_note),
                )
                items + info
            }
        },
    )

    fun stetify(flow: Flow<List<LoginStateItem>>) = flow
        .map { items ->
            items
                .mapNotNull { item ->
                    val stateHolder = item as? LoginStateItem.HasState<Any?>
                        ?: return@mapNotNull null

                    val state = stateHolder.state
                    state
                        .flow
                        .map { v ->
                            state.populator
                                .partially2(v)
                        }
                }
        }

    val outputFlow = combine(
        stetify(envHeadersFlow),
        stetify(flowOf(globalItems)),
        stetify(flowOf(envServerItems)),
        clientCredentialFlow
            .map { credentials ->
                val transform = fun(oldState: CreateRequest): CreateRequest {
                    var state = oldState

                    // Email
                    kotlin.run {
                        val error = credentials.email is Validated.Failure
                        if (error) return state.copy(error = true)
                        state = state.copy(username = credentials.email.model)
                    }
                    // Password
                    kotlin.run {
                        val error = credentials.password is Validated.Failure
                        if (error) return state.copy(error = true)
                        state = state.copy(password = credentials.password.model)
                    }
                    // Client secret
                    kotlin.run {
                        val error = credentials.clientSecret is Validated.Failure
                        if (error) return state.copy(error = true)
                        state = state.copy(clientSecret = credentials.clientSecret?.model)
                    }

                    return state
                }
                listOf(flowOf(transform))
            },
    ) { arr ->
        arr.toList().flatten()
    }
        .flatMapLatest { it.combineToList() }
        .map { populators ->
            populators.fold(
                initial = CreateRequest(),
            ) { state, transform ->
                // If the model already has an error, then we can not
                // use it and to save processing power we can abort
                // populating it.
                if (state.error) state else transform(state)
            }
        }
        .distinctUntilChanged()

    combine(
        clientCredentialFlow,
        regionFlow.map { it is LoginRegion.Custom }.distinctUntilChanged(),
        outputFlow,
        envHeadersFlow,
        actionExecutor.isExecutingFlow,
    ) { (validatedEmail, validatedPassword, clientSecret), showCustomEnv, output, items, taskIsExecuting ->
        val canLogin = validatedEmail is Validated.Success &&
                validatedPassword is Validated.Success &&
                !output.error &&
                !taskIsExecuting

        val emailField = TextFieldModel2.of(
            state = emailState,
            validated = validatedEmail,
            onChange = emailState::value::set
                .takeUnless { !args.emailEditable || taskIsExecuting },
        )
        val passwordField = TextFieldModel2.of(
            state = passwordState,
            validated = validatedPassword,
            onChange = passwordState::value::set
                .takeUnless { !args.passwordEditable || taskIsExecuting },
        )
        val clientSecretField = clientSecret?.let {
            TextFieldModel2.of(
                state = clientSecretState,
                validated = it,
                onChange = clientSecretState::value::set
                    .takeUnless { !args.clientSecretEditable || taskIsExecuting },
            )
        }
        LoginState(
            effects = LoginState.SideEffect(
                onSuccessFlow = onSuccessFlow,
                onErrorFlow = onErrorFlow,
            ),
            email = emailField,
            password = passwordField,
            clientSecret = clientSecretField,
            showCustomEnv = showCustomEnv,
            regionItems = globalItems,
            items = envServerItems + items,
            isLoading = taskIsExecuting,
            onLoginClick = if (canLogin) {
                {
                    val env = if (args.envEditable) {
                        when (val r = output.region) {
                            is LoginRegion.Predefined -> {
                                ServerEnv(
                                    region = r.region,
                                )
                            }

                            is LoginRegion.Custom -> {
                                ServerEnv(
                                    baseUrl = output.baseUrl.orEmpty().let(::ensureUrlSchema),
                                    webVaultUrl = output.webVaultUrl.orEmpty()
                                        .let(::ensureUrlSchema),
                                    apiUrl = output.apiUrl.orEmpty().let(::ensureUrlSchema),
                                    identityUrl = output.identityUrl.orEmpty()
                                        .let(::ensureUrlSchema),
                                    iconsUrl = output.iconsUrl.orEmpty().let(::ensureUrlSchema),
                                    headers = output.headers,
                                )
                            }

                            null -> {
                                ServerEnv(
                                    region = ServerEnv.Region.default,
                                )
                            }
                        }
                    } else {
                        args.env
                    }
                    val io = addAccount(
                        args.accountId, // account id
                        env, // environment
                        null, // two-factor token
                        clientSecret?.model, // client secret
                        validatedEmail.model, // email
                        validatedPassword.model, // password
                    )
                        .handleErrorTap { e ->
                            // We should only handle api exceptions
                            if (e !is ApiException) return@handleErrorTap
                            when (val t = e.type) {
                                is ApiException.Type.TwoFaRequired -> {
                                    val otpRequiredArgs = LoginTwofaRoute.Args(
                                        accountId = args.accountId,
                                        providers = t.providers,
                                        clientSecret = clientSecret?.model,
                                        email = validatedEmail.model,
                                        password = validatedPassword.model,
                                        env = env,
                                    )
                                    val event = LoginEvent.Error.OtpRequired(otpRequiredArgs)
                                    onErrorFlow.emit(event)
                                }

                                is ApiException.Type.CaptchaRequired -> {
                                    val fingerprint = IdentityRequestFingerprint(
                                        email = validatedEmail.model,
                                        password = validatedPassword.model,
                                        baseUrl = env.baseUrl,
                                        identityUrl = env.identityUrl,
                                    )
                                    failedIdentityRequestFingerprintSink.value = fingerprint
                                }

                                else -> {
                                    // TODO: Do we ned to reset the password on error?
                                    //  Everyone does that, but i'm not sure if
                                    //  that is very helpful.
                                    passwordState.value = ""
                                }
                            }
                        }
                        .effectTap {
                            onSuccessFlow.emit(Unit)
                        }
                    actionExecutor.execute(io)
                }
            } else {
                null
            },
        ).let { Loadable.Ok(it) }
    }
}

private fun ensureUrlSchema(url: String) =
    if (url.isBlank() || url.contains("://")) {
        // The url contains a schema, return
        // it as it as.
        url
    } else {
        val newUrl = "https://$url"
        newUrl
    }

class AddStateItemFieldFactory(
    private val readOnlyFlow: Flow<Boolean>,
) : Foo2Factory<LoginStateItem.HttpHeader, ServerHeader> {
    override val type: String = "header"

    override fun RememberStateFlowScope.release(key: String) {
        clearPersistedFlow("$key.label")
        clearPersistedFlow("$key.text")
    }

    override suspend fun RememberStateFlowScope.add(
        coroutineScope: CoroutineScope,
        key: String,
        initial: ServerHeader?,
    ): LoginStateItem.HttpHeader {
        val labelSink = mutablePersistedFlow("$key.label") {
            initial?.key.orEmpty()
        }
        val labelMutableState = mutableComposeState(labelSink)
        val labelFlow = labelSink
            .map { label ->
                val error = if (label.isBlank()) {
                    translate(Res.strings.error_must_not_be_blank)
                } else {
                    null
                }
                TextFieldModel2(
                    text = label,
                    hint = translate(Res.strings.addaccount_http_header_key_label),
                    error = error,
                    state = labelMutableState,
                    onChange = labelMutableState::value::set,
                )
            }
            .readOnly(readOnlyFlow)

        val textSink = mutablePersistedFlow("$key.text") {
            initial?.value.orEmpty()
        }
        val textMutableState = mutableComposeState(textSink)
        val textFlow = textSink
            .map { text ->
                val error = if (!REGEX_US_ASCII.matches(text)) {
                    translate(Res.strings.error_must_contain_only_us_ascii_chars)
                } else {
                    null
                }
                TextFieldModel2(
                    text = text,
                    hint = translate(Res.strings.addaccount_http_header_value_label),
                    error = error,
                    state = textMutableState,
                    onChange = textMutableState::value::set,
                )
            }
            .readOnly(readOnlyFlow)

        val stateFlow = combine(
            labelFlow,
            textFlow,
        ) { labelField, textField ->
            LoginStateItem.HttpHeader.State(
                label = labelField,
                text = textField,
            )
        }
            .persistingStateIn(
                scope = coroutineScope,
                started = SharingStarted.Lazily,
            )
        return LoginStateItem.HttpHeader(
            id = key,
            state = LocalStateItem(
                flow = stateFlow,
                populator = { state ->
                    val error = state.label.error != null || state.text.error != null
                    if (error) {
                        return@LocalStateItem copy(error = true)
                    }

                    val header = ServerHeader(
                        key = state.label.text,
                        value = state.text.text,
                    )
                    copy(headers = headers.add(header))
                },
            ),
        )
    }
}

interface Foo2Factory<T, Default> where T : LoginStateItem, T : LoginStateItem.HasOptions<T> {
    val type: String

    suspend fun RememberStateFlowScope.add(
        coroutineScope: CoroutineScope,
        key: String,
        initial: Default?,
    ): T

    fun RememberStateFlowScope.release(key: String)
}

data class Foo2Type(
    val type: String,
    val name: String,
)

data class Foo2Persistable(
    val key: String,
    val type: String,
) : Serializable

data class Foo2InitialState<Argument>(
    val items: List<Item<Argument>>,
) {
    data class Item<Argument>(
        val key: String,
        val type: String,
        val argument: Argument? = null,
    )
}

fun <T, Argument> RememberStateFlowScope.foo3(
    scope: String,
    initial: List<Argument>,
    initialType: (Argument) -> String,
    factories: List<Foo2Factory<T, Argument>>,
    afterList: MutableList<LoginStateItem>.() -> Unit,
    extra: FieldBakeryScope<Argument>.() -> Flow<List<LoginStateItem>> = {
        flowOf(emptyList())
    },
): Flow<List<LoginStateItem>> where T : LoginStateItem, T : LoginStateItem.HasOptions<T> {
    val initialState = Foo2InitialState(
        items = initial
            .associateBy {
                UUID.randomUUID().toString()
            }
            .map { entry ->
                Foo2InitialState.Item(
                    key = entry.key,
                    type = initialType(entry.value),
                    argument = entry.value,
                )
            },
    )
    return foo<T, Argument>(
        scope = scope,
        initialState = initialState,
        entryAdd = { coroutineScope, (key, type), arg ->
            val factory = factories.first { it.type == type }
            with(factory) {
                add(coroutineScope, key, arg)
            }
        },
        entryRelease = { (key, type) ->
            val factory = factories.first { it.type == type }
            with(factory) {
                release(key)
            }
        },
        afterList = afterList,
        extra = extra,
    )
}

interface FieldBakeryScope<Argument> {
    fun moveUp(key: String)

    fun moveDown(key: String)

    fun delete(key: String)

    fun add(type: String, arg: Argument?)
}

private fun <Argument> FieldBakeryScope<Argument>.typeBasedAddItem(
    scope: String,
    addText: String,
    typesFlow: Flow<List<Foo2Type>>,
): Flow<List<LoginStateItem>> = typesFlow
    .map { types ->
        if (types.isEmpty()) {
            return@map null // hide add item
        }

        val actions = types
            .map { type ->
                FlatItemAction(
                    title = type.name,
                    onClick = {
                        add(type.type, null)
                    },
                )
            }
        LoginStateItem.Add(
            id = "$scope.add",
            text = addText,
            actions = actions,
        )
    }
    .map { listOfNotNull(it) }

fun <T, Argument> RememberStateFlowScope.foo(
    // scope name,
    scope: String,
    initialState: Foo2InitialState<Argument>,
    entryAdd: suspend RememberStateFlowScope.(CoroutineScope, Foo2Persistable, Argument?) -> T,
    entryRelease: RememberStateFlowScope.(Foo2Persistable) -> Unit,
    afterList: MutableList<LoginStateItem>.() -> Unit,
    extra: FieldBakeryScope<Argument>.() -> Flow<List<LoginStateItem>> = {
        flowOf(emptyList())
    },
): Flow<List<LoginStateItem>> where T : LoginStateItem, T : LoginStateItem.HasOptions<T> {
    var inMemoryArguments = initialState
        .items
        .mapNotNull {
            if (it.argument != null) {
                it.key to it.argument
            } else {
                null
            }
        }
        .toMap()
        .toPersistentMap()

    val persistableSink = mutablePersistedFlow("$scope.keys") {
        initialState
            .items
            .map {
                Foo2Persistable(
                    key = it.key,
                    type = it.type,
                )
            }
    }

    fun move(key: String, offset: Int) = persistableSink.update { state ->
        val i = state.indexOfFirst { it.key == key }
        if (
            i == -1 ||
            i + offset < 0 ||
            i + offset >= state.size
        ) {
            return@update state
        }

        val newState = state.toMutableList()
        val item = newState.removeAt(i)
        newState.add(i + offset, item)
        newState
    }

    fun moveUp(key: String) = move(key, offset = -1)

    fun moveDown(key: String) = move(key, offset = 1)

    fun delete(key: String) = persistableSink.update { state ->
        inMemoryArguments = inMemoryArguments.remove(key)

        val i = state.indexOfFirst { it.key == key }
        if (i == -1) return@update state

        val newState = state.toMutableList()
        newState.removeAt(i)
        newState
    }

    fun add(type: String, arg: Argument?) {
        val key = "$scope.items." + UUID.randomUUID().toString()
        // Remember the argument, so we can grab it and construct something
        // persistent from it.
        if (arg != null) {
            inMemoryArguments = inMemoryArguments.put(key, arg)
        }

        val newEntry = Foo2Persistable(
            key = key,
            type = type,
        )
        persistableSink.update { state ->
            state + newEntry
        }
    }

    val scope2 = object : FieldBakeryScope<Argument> {
        override fun moveUp(key: String) = moveUp(key)

        override fun moveDown(key: String) = moveDown(key)

        override fun delete(key: String) = delete(key)

        override fun add(type: String, arg: Argument?) = add(type, arg)
    }

    data class Foo2Scoped<T>(
        val perst: Foo2Persistable,
        val scope: CoroutineScope,
        val v: T,
    )

    val keysFlow = persistableSink
        .scan(
            initial = listOf<Foo2Scoped<T>>(),
        ) { state, new ->
            val out = mutableListOf<Foo2Scoped<T>>()
            // Find if a new entry has a different
            // type comparing to the old one.
            new.forEach { perst ->
                val existingEntry = state.firstOrNull { it.perst.key == perst.key }
                val shouldAdd = when (val existingType = existingEntry?.perst?.type) {
                    perst.type -> {
                        out += existingEntry
                        false // do no add
                    }

                    null -> true // add

                    else -> {
                        val item = Foo2Persistable(perst.key, existingType)
                        entryRelease(item)
                        // clear the scope that we used to construct fields
                        existingEntry.scope.cancel()
                        true // add
                    }
                }
                if (shouldAdd) {
                    val itemCoroutineScope = screenScope + Job()
                    val itemDefaultArg = inMemoryArguments[perst.key]
                    out += Foo2Scoped(
                        perst = perst,
                        scope = itemCoroutineScope,
                        v = entryAdd(itemCoroutineScope, perst, itemDefaultArg),
                    )
                }
            }
            // Clean-up removed entries.
            state.forEach { i ->
                val removed = new.none { it.key == i.perst.key }
                if (removed) {
                    val item = Foo2Persistable(i.perst.key, i.perst.type)
                    entryRelease(item)
                    // clear the scope that we used to construct fields
                    i.scope.cancel()
                }
            }

            out
        }
    val itemsFlow = keysFlow
        .map { state ->
            state.mapIndexed { index, entry ->
                val item = entry.v

                // Appends list-specific options to be able to reorder
                // the list or remove an item.
                val options = item.options.toMutableList()
                if (index > 0) {
                    options += FlatItemAction(
                        icon = Icons.Outlined.ArrowUpward,
                        title = translate(Res.strings.list_move_up),
                        onClick = ::moveUp.partially1(entry.perst.key),
                    )
                }
                if (index < state.size - 1) {
                    options += FlatItemAction(
                        icon = Icons.Outlined.ArrowDownward,
                        title = translate(Res.strings.list_move_down),
                        onClick = ::moveDown.partially1(entry.perst.key),
                    )
                }
                options += FlatItemAction(
                    icon = Icons.Outlined.DeleteForever,
                    title = translate(Res.strings.list_remove),
                    onClick = {
                        val intent = createConfirmationDialogIntent(
                            icon = icon(Icons.Outlined.DeleteForever),
                            title = translate(Res.strings.list_remove_confirmation_title),
                        ) {
                            delete(entry.perst.key)
                        }
                        navigate(intent)
                    },
                )

                item.withOptions(options)
            }
        }
    val extraFlow = extra(scope2)
    return combine(
        itemsFlow,
        extraFlow,
    ) { items, customItems ->
        val out = items
            .widen<LoginStateItem, T>()
            .toMutableList()
        out += customItems
        out.apply(afterList)
    }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(5000L), replay = 1)
}

private fun Flow<TextFieldModel2>.readOnly(flow: Flow<Boolean>) = this
    .combine(flow) { textField, readOnly ->
        if (readOnly) {
            textField.copy(onChange = null)
        } else {
            textField
        }
    }
