package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.CheckS3Connection
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginState
import com.artemchep.keyguard.feature.auth.keepass.keePassLoginStateProducer
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.s3.S3SettingsResult
import com.artemchep.keyguard.feature.s3.S3SettingsRoute
import com.artemchep.keyguard.feature.s3.S3SettingsState
import com.artemchep.keyguard.feature.s3.s3SettingsStateProducer
import com.artemchep.keyguard.feature.webdav.WebDavSettingsResult
import com.artemchep.keyguard.feature.webdav.WebDavSettingsRoute
import com.artemchep.keyguard.feature.webdav.WebDavSettingsState
import com.artemchep.keyguard.feature.webdav.webDavSettingsStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.resultRouteOrNull
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.core.onFilePickerResult
import com.artemchep.keyguard.apple.core.toFilePickerRequest
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.artemchep.keyguard.apple.core.DetailSession
import kotlin.uuid.Uuid

private const val LOCATION_LOCAL = "local"
private const val LOCATION_WEBDAV = "webdav"
private const val LOCATION_S3 = "s3"

/** One KeePass sign-in form, including its files and its WebDAV and S3 children. Main-confined. */
class KeePassLoginSession internal constructor(
    private val ctx: CoreContext,
) {
    private val form = DetailSession<KeePassLoginSnapshot, KeePassActions>(onDispose = ::dispose)

    // Deliberately separate from the add-form picker maps: the KeePass
    // resolution contract differs (original url + bookmark token, never a
    // temp copy), so the two must not share a Swift presentation path.
    private var onFilePickerRequest: ((KeePassFilePickerRequest) -> Unit)? = null
    private val filePickerHandlers = mutableMapOf<String, (FilePickerResult?) -> Unit>()
    private var filePickerRequestCounter = 0

    private val webDav = SettingsSheet<WebDavSettingsSnapshot, WebDavSettingsState, WebDavSettingsResult>(
        name = "webdav_settings",
    )

    private val s3 = SettingsSheet<S3SettingsSnapshot, S3SettingsState, S3SettingsResult>(
        name = "s3_settings",
    )

    fun observe(
        onChange: (KeePassLoginSnapshot) -> Unit,
        onClose: () -> Unit,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
        onS3Change: (S3SettingsSnapshot?) -> Unit,
    ): KeyguardCancellable = form.observe(onChange, onClose) { publish, complete ->
        val onSuccess = form.gated<Unit> {
            onWebDavChange(null)
            onS3Change(null)
            complete()
        }
        observeLogin(publish, onSuccess, form.gated(::handleFilePickerIntent), onWebDavChange, onS3Change)
    }

    fun close() = form.close()

    private fun dispose() {
        filePickerHandlers.clear()
        onFilePickerRequest = null
        cancelWebDavSettings()
        cancelS3Settings()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLogin(
        publish: (KeePassLoginSnapshot, KeePassActions) -> Unit,
        onSuccess: (Unit) -> Unit,
        onFilePickerIntent: (FilePickerIntent<*>) -> Unit,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
        onS3Change: (S3SettingsSnapshot?) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = { onSuccess(Unit) },
        ) { state ->
            // AddKeePassAccount is session-scoped; the session scope also
            // resolves global bindings such as the WebDAV connection check.
            val addKeepassAccount = state.sessionKoin.get<AddKeePassAccount>()
            val checkWebDavConnection = state.sessionKoin.get<CheckWebDavConnection>()
            val checkS3Connection = state.sessionKoin.get<CheckS3Connection>()
            // The WebDAV child producer must die with the session (a lock mid-
            // sheet must tear it down), so anchor it to this observer scope.
            val sessionScope = this
            // The producer navigates to the WebDAV or S3 settings route (wrapped
            // in a result receiver) when the user picks a remote location; claim
            // it and run the settings producer as a child instead of dropping it.
            val interceptor = interceptor@{ intent: NavigationIntent ->
                val webdav = intent.resultRouteOrNull<WebDavSettingsRoute, WebDavSettingsResult>()
                if (webdav != null) {
                    val (route, transmitter) = webdav
                    ctx.scope.launch {
                        form.runIfOpen {
                            startWebDavSettings(sessionScope, route, transmitter, checkWebDavConnection, onWebDavChange)
                        }
                    }
                    return@interceptor true
                }
                val s3 = intent.resultRouteOrNull<S3SettingsRoute, S3SettingsResult>()
                    ?: return@interceptor false
                val (route, transmitter) = s3
                ctx.scope.launch {
                    form.runIfOpen {
                        startS3Settings(sessionScope, route, transmitter, checkS3Connection, onS3Change)
                    }
                }
                true
            }
            val producerFlow = ctx.koin.newHeadlessStateFlowScope("keepasslogin", this, interceptor)
                .keePassLoginStateProducer(
                    addKeepassAccount = addKeepassAccount,
                )
            // Single shared copy of the latest top-level state. The producer is
            // cold and double-collecting it would run the form twice, so fan
            // out from one StateFlow instead.
            val latest = MutableStateFlow<KeePassLoginState?>(null)
            launch {
                producerFlow.collect { loadable -> latest.value = loadable.getOrNull() }
            }

            // One-shot side effects, mirroring the Compose screen's
            // CollectedEffect handlers.
            launch {
                latest.filterNotNull()
                    .map { it.sideEffects }
                    .distinctUntilChanged()
                    .flatMapLatest { it.onSuccessFlow }
                    .collect { ctx.publishOnMain { onSuccess(Unit) } }
            }
            launch {
                latest.filterNotNull()
                    .map { it.sideEffects }
                    .distinctUntilChanged()
                    .flatMapLatest { it.filePickerIntentFlow }
                    .collect { intent -> ctx.publishOnMain { onFilePickerIntent(intent) } }
            }

            // Snapshot stream. Everything except isLoading lives in inner
            // StateFlows the top-level state does NOT re-emit for, so combine
            // them in: any tab / location / file / password / action change
            // then re-runs the builder and pushes a fresh snapshot.
            latest.filterNotNull()
                .flatMapLatest { login ->
                    val filesFlow = combine(
                        login.tabsState,
                        login.databaseLocationState,
                        login.dbFileState,
                        login.keyFileState,
                    ) { tabs, location, dbFile, keyFile ->
                        InnerState(tabs, location, dbFile, keyFile)
                    }
                    combine(
                        filesFlow,
                        login.password,
                        login.actionState,
                    ) { inner, password, action ->
                        SnapshotInput(login, inner, password, action)
                    }
                }
                .throttleLatest()
                .map { input ->
                    val actions = KeePassActions(input)
                    buildSnapshot(input, leContext, actions.fields) to actions
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }

    /** The form's callbacks, published atomically with the snapshot built from the same [input]. */
    private class KeePassActions(val input: SnapshotInput) {
        val fields = LinkedHashMap<String, (String) -> Unit>()
    }

    private class InnerState(
        val tabs: KeePassLoginState.Tabs,
        val location: KeePassLoginState.DatabaseLocation,
        val dbFile: KeePassLoginState.FileItem,
        val keyFile: KeePassLoginState.FileItem,
    )

    private class SnapshotInput(
        val login: KeePassLoginState,
        val inner: InnerState,
        val password: TextFieldModel,
        val action: KeePassLoginState.Action?,
    )

    private suspend fun buildSnapshot(
        input: SnapshotInput,
        leContext: LeContext,
        fieldHandlers: LinkedHashMap<String, (String) -> Unit>,
    ): KeePassLoginSnapshot {
        val tabs = input.inner.tabs.items.map { tab ->
            KeePassTabSnapshot(
                key = tab.key,
                title = textResource(tab.title, leContext),
                checked = tab.checked,
            )
        }
        val locations = input.inner.location.items.map { item ->
            KeePassLocationSnapshot(
                key = item.type.toLocationKey(),
                title = textResource(item.title, leContext),
                checked = item.checked,
            )
        }
        return KeePassLoginSnapshot(
            tabs = tabs,
            locations = locations,
            dbFile = input.inner.dbFile.file?.toFileSnapshot(),
            canClearDbFile = input.inner.dbFile.onClear != null,
            keyFile = input.inner.keyFile.file?.toFileSnapshot(),
            canClearKeyFile = input.inner.keyFile.onClear != null,
            password = input.password.toFieldSnapshot(fieldHandlers, id = "keepass.password"),
            canSubmit = input.action != null,
            isLoading = input.login.isLoading,
        )
    }

    private fun KeePassLoginState.DatabaseLocation.Type.toLocationKey() = when (this) {
        KeePassLoginState.DatabaseLocation.Type.Local -> LOCATION_LOCAL
        KeePassLoginState.DatabaseLocation.Type.WebDav -> LOCATION_WEBDAV
        KeePassLoginState.DatabaseLocation.Type.S3 -> LOCATION_S3
    }

    private fun KeePassLoginState.FileItem.File.toFileSnapshot() = KeePassFileSnapshot(
        name = name,
        size = size ?: -1L,
    )

    fun selectKeePassTab(key: String) = form.withActions { actions ->
        actions.input.inner.tabs.items.firstOrNull { it.key == key }?.onClick?.invoke()
    }

    fun selectKeePassLocation(key: String) = form.withActions { actions ->
        actions.input.inner.location.items.firstOrNull { it.type.toLocationKey() == key }?.onClick?.invoke()
    }

    fun pickKeePassDbFile() = form.withActions { it.input.inner.dbFile.onClick() }

    fun clearKeePassDbFile() = form.withActions { it.input.inner.dbFile.onClear?.invoke() }

    fun pickKeePassKeyFile() = form.withActions { it.input.inner.keyFile.onClick() }

    fun clearKeePassKeyFile() = form.withActions { it.input.inner.keyFile.onClear?.invoke() }

    fun setKeePassPassword(text: String) = form.withActions { it.fields["keepass.password"]?.invoke(text) }

    fun submitKeePassLogin() = form.withActions { it.input.action?.onClick?.invoke() }

    fun setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Unit)?) =
        form.runIfOpen { onFilePickerRequest = handler }

    private fun handleFilePickerIntent(intent: FilePickerIntent<*>) {
        val requestId = "kfp:${filePickerRequestCounter++}"
        filePickerHandlers[requestId] = intent.onFilePickerResult
        onFilePickerRequest?.invoke(intent.toFilePickerRequest(requestId, ::KeePassFilePickerRequest))
    }

    fun resolveKeePassFilePicker(
        requestId: String,
        uri: String,
        name: String?,
        size: Long,
        accessToken: String?,
    ) {
        val handler = filePickerHandlers.remove(requestId) ?: return
        handler(filePickerResultOf(uri, name, size, accessToken))
    }

    fun cancelKeePassFilePicker(requestId: String) {
        val handler = filePickerHandlers.remove(requestId) ?: return
        handler(null)
    }

    /**
     * One remote settings sheet at a time, run as a child of the sign-in form.
     * Swift echoes the sheet id, so a dismissed sheet never edits its replacement.
     */
    private inner class SettingsSheet<Snapshot : Any, State : Any, Result>(
        private val name: String,
    ) {
        private var id: String? = null
        private var session: DetailSession<Snapshot, State>? = null

        fun start(
            sessionScope: CoroutineScope,
            transmitter: RouteResultTransmitter<Result>,
            onChange: (Snapshot?) -> Unit,
            produce: suspend RememberStateFlowScope.(RouteResultTransmitter<Result>) -> Flow<State>,
            toSnapshot: (id: String, state: State) -> Snapshot,
        ) {
            cancel()
            val id = Uuid.random().toString()
            val child = DetailSession<Snapshot, State>()
            this.id = id
            this.session = child
            val onSaved = child.gated<Result> { result ->
                transmitter(result)
                cancel()
                onChange(null)
            }
            val wrappedTransmitter = object : RouteResultTransmitter<Result> {
                override fun invoke(p1: Result) {
                    ctx.scope.launch { onSaved(p1) }
                }
            }
            child.observe(onChange) { publish ->
                KeyguardCancellable(
                    sessionScope.launch {
                        ctx.koin.newHeadlessStateFlowScope(name, this)
                            .produce(wrappedTransmitter)
                            .throttleLatest()
                            .map { state -> toSnapshot(id, state) to state }
                            .collectOnMain { (snapshot, state) -> publish(snapshot, state) }
                    },
                )
            }
        }

        fun withActions(sessionId: String, block: (State) -> Unit) {
            session?.takeIf { id == sessionId }?.withActions(block)
        }

        fun cancel() {
            session?.close()
            session = null
            id = null
        }
    }

    private fun startWebDavSettings(
        sessionScope: CoroutineScope,
        route: WebDavSettingsRoute,
        transmitter: RouteResultTransmitter<WebDavSettingsResult>,
        checkWebDavConnection: CheckWebDavConnection,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
    ) = webDav.start(
        sessionScope = sessionScope,
        transmitter = transmitter,
        onChange = onWebDavChange,
        produce = { wrappedTransmitter ->
            webDavSettingsStateProducer(
                route = route,
                transmitter = wrappedTransmitter,
                checkWebDavConnection = checkWebDavConnection,
            )
        },
    ) { id, state ->
        WebDavSettingsSnapshot(
            id = id,
            url = state.url.value,
            username = state.username.value,
            password = state.password.value,
            errorKind = state.error?.name,
            isTestingConnection = state.isTestingConnection,
        )
    }

    private fun withWebDav(sessionId: String, block: (WebDavSettingsState) -> Unit) =
        webDav.withActions(sessionId, block)

    fun setWebDavField(sessionId: String, id: String, text: String) = withWebDav(sessionId) { state ->
        when (id) {
            // The URL is written through the producer's own setter, which also
            // invalidates the remembered WebDAV browse root.
            "url" -> state.onUrlChange(text)
            "username" -> state.username.value = text
            "password" -> state.password.value = text
        }
    }

    fun submitWebDavSettings(sessionId: String) = withWebDav(sessionId) { it.onSave() }

    fun testWebDavConnection(sessionId: String) = withWebDav(sessionId) { it.onTestConnection() }

    fun cancelWebDavSettings() = webDav.cancel()

    private fun startS3Settings(
        sessionScope: CoroutineScope,
        route: S3SettingsRoute,
        transmitter: RouteResultTransmitter<S3SettingsResult>,
        checkS3Connection: CheckS3Connection,
        onS3Change: (S3SettingsSnapshot?) -> Unit,
    ) = s3.start(
        sessionScope = sessionScope,
        transmitter = transmitter,
        onChange = onS3Change,
        produce = { wrappedTransmitter ->
            s3SettingsStateProducer(
                route = route,
                transmitter = wrappedTransmitter,
                checkS3Connection = checkS3Connection,
            )
        },
    ) { id, state ->
        S3SettingsSnapshot(
            id = id,
            endpoint = state.endpoint.value,
            region = state.region.value,
            bucket = state.bucket.value,
            key = state.path.value,
            accessKeyId = state.accessKeyId.value,
            secretAccessKey = state.secretAccessKey.value,
            pathStyle = state.pathStyle.value,
            errorKind = state.error?.name,
            isTestingConnection = state.isTestingConnection,
            fieldErrors = state.validation.errors.map { S3SettingsFieldErrorSnapshot(it.field, it.name) },
            validationRequest = state.validation.request,
            validationField = state.validation.focusField,
        )
    }

    private fun withS3(sessionId: String, block: (S3SettingsState) -> Unit) =
        s3.withActions(sessionId, block)

    fun setS3Field(sessionId: String, id: String, text: String) = withS3(sessionId) { state ->
        val field = when (id) {
            "endpoint" -> state.endpoint
            "region" -> state.region
            "bucket" -> state.bucket
            "key" -> state.path
            "accessKeyId" -> state.accessKeyId
            "secretAccessKey" -> state.secretAccessKey
            else -> return@withS3
        }
        if (field.value != text) {
            field.value = text
            state.onFieldEdited(id)
        }
    }

    fun blurS3Field(sessionId: String, id: String) = withS3(sessionId) { it.onFieldBlurred(id) }

    fun setS3PathStyle(sessionId: String, value: Boolean) = withS3(sessionId) { state ->
        if (state.pathStyle.value != value) {
            state.pathStyle.value = value
            state.onFieldEdited("bucket")
        }
    }

    fun submitS3Settings(sessionId: String) = withS3(sessionId) { it.onSave() }

    fun testS3Connection(sessionId: String) = withS3(sessionId) { it.onTestConnection() }

    fun cancelS3Settings() = s3.cancel()
}
