package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginState
import com.artemchep.keyguard.feature.auth.keepass.keePassLoginStateProducer
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
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

/** One KeePass sign-in form, including its files and WebDAV child. Main-confined. */
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

    /** The WebDAV settings sheet; Swift echoes [id] so a dismissed sheet never edits its replacement. */
    private class WebDavChild(
        val id: String,
        val session: DetailSession<WebDavSettingsSnapshot, WebDavSettingsState>,
    )

    private var webDav: WebDavChild? = null

    fun observe(
        onChange: (KeePassLoginSnapshot) -> Unit,
        onClose: () -> Unit,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
    ): KeyguardCancellable = form.observe(onChange, onClose) { publish, complete ->
        val onSuccess = form.gated<Unit> {
            onWebDavChange(null)
            complete()
        }
        observeLogin(publish, onSuccess, form.gated(::handleFilePickerIntent), onWebDavChange)
    }

    fun close() = form.close()

    private fun dispose() {
        filePickerHandlers.clear()
        onFilePickerRequest = null
        cancelWebDavSettings()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLogin(
        publish: (KeePassLoginSnapshot, KeePassActions) -> Unit,
        onSuccess: (Unit) -> Unit,
        onFilePickerIntent: (FilePickerIntent<*>) -> Unit,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = { onSuccess(Unit) },
        ) { state ->
            // AddKeePassAccount is session-scoped; the session scope also
            // resolves global bindings such as the WebDAV connection check.
            val addKeepassAccount = state.sessionKoin.get<AddKeePassAccount>()
            val checkWebDavConnection = state.sessionKoin.get<CheckWebDavConnection>()
            // The WebDAV child producer must die with the session (a lock mid-
            // sheet must tear it down), so anchor it to this observer scope.
            val sessionScope = this
            // The producer navigates to the WebDAV settings route (wrapped in a
            // result receiver) when the user picks the WebDAV location; claim it
            // and run the settings producer as a child instead of dropping it.
            val interceptor = interceptor@{ intent: NavigationIntent ->
                val webdav = intent.resultRouteOrNull<WebDavSettingsRoute, WebDavSettingsResult>()
                    ?: return@interceptor false
                val (route, transmitter) = webdav
                ctx.scope.launch {
                    form.runIfOpen {
                        startWebDavSettings(sessionScope, route, transmitter, checkWebDavConnection, onWebDavChange)
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
            isWebDav = input.inner.location.type == KeePassLoginState.DatabaseLocation.Type.WebDav,
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

    private fun startWebDavSettings(
        sessionScope: CoroutineScope,
        route: WebDavSettingsRoute,
        transmitter: RouteResultTransmitter<WebDavSettingsResult>,
        checkWebDavConnection: CheckWebDavConnection,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
    ) {
        cancelWebDavSettings()
        val id = Uuid.random().toString()
        val child = DetailSession<WebDavSettingsSnapshot, WebDavSettingsState>()
        webDav = WebDavChild(id, child)
        val onSaved = child.gated<WebDavSettingsResult> { result ->
            transmitter(result)
            cancelWebDavSettings()
            onWebDavChange(null)
        }
        val wrappedTransmitter = object : RouteResultTransmitter<WebDavSettingsResult> {
            override fun invoke(p1: WebDavSettingsResult) {
                ctx.scope.launch { onSaved(p1) }
            }
        }
        child.observe(onWebDavChange) { publish ->
            KeyguardCancellable(
                sessionScope.launch {
                    ctx.koin.newHeadlessStateFlowScope("webdav_settings", this)
                        .webDavSettingsStateProducer(
                            route = route,
                            transmitter = wrappedTransmitter,
                            checkWebDavConnection = checkWebDavConnection,
                        )
                        .throttleLatest()
                        .map { state ->
                            WebDavSettingsSnapshot(
                                id = id,
                                url = state.url.value,
                                username = state.username.value,
                                password = state.password.value,
                                errorKind = state.error?.name,
                                isTestingConnection = state.isTestingConnection,
                            ) to state
                        }
                        .collectOnMain { (snapshot, state) -> publish(snapshot, state) }
                },
            )
        }
    }

    private fun withWebDav(sessionId: String, block: (WebDavSettingsState) -> Unit) {
        webDav?.takeIf { it.id == sessionId }?.session?.withActions(block)
    }

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

    fun cancelWebDavSettings() {
        webDav?.session?.close()
        webDav = null
    }
}
