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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val LOCATION_LOCAL = "local"
private const val LOCATION_WEBDAV = "webdav"

internal class KeePassLoginController(
    private val ctx: CoreContext,
) {
    // Main-confined mirrors of the latest producer state; Swift always calls
    // the imperative set* / submit* methods from the main thread.
    private var latestState: KeePassLoginState? = null
    private var latestLocation: KeePassLoginState.DatabaseLocation? = null
    private var latestDbFile: KeePassLoginState.FileItem? = null
    private var latestKeyFile: KeePassLoginState.FileItem? = null
    private var latestAction: KeePassLoginState.Action? = null
    private var latestTabs: KeePassLoginState.Tabs? = null
    private var fieldHandlers: Map<String, (String) -> Unit> = emptyMap()

    // Deliberately separate from the add-form picker maps: the KeePass
    // resolution contract differs (original url + bookmark token, never a
    // temp copy), so the two must not share a Swift presentation path.
    private var onFilePickerRequest: ((KeePassFilePickerRequest) -> Unit)? = null
    private val filePickerHandlers = mutableMapOf<String, (FilePickerResult?) -> Unit>()
    private var filePickerRequestCounter = 0

    private var latestWebDavState: WebDavSettingsState? = null
    private var webDavJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeKeePassLogin(
        onChange: (KeePassLoginSnapshot) -> Unit,
        onSuccess: () -> Unit,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                latestState = null
                latestLocation = null
                latestDbFile = null
                latestKeyFile = null
                latestAction = null
                latestTabs = null
                fieldHandlers = emptyMap()
                latestWebDavState = null
                webDavJob = null
                onWebDavChange(null)
                onChange(KeePassLoginSnapshot.empty)
            },
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
                startWebDavSettings(
                    sessionScope = sessionScope,
                    route = route,
                    transmitter = transmitter,
                    checkWebDavConnection = checkWebDavConnection,
                    onWebDavChange = onWebDavChange,
                )
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
                producerFlow.collect { loadable ->
                    val loginState = loadable.getOrNull()
                    latest.value = loginState
                    ctx.publishOnMain {
                        latestState = loginState
                    }
                }
            }

            // One-shot side effects, mirroring the Compose screen's
            // CollectedEffect handlers.
            launch {
                latest.filterNotNull()
                    .map { it.sideEffects }
                    .distinctUntilChanged()
                    .flatMapLatest { it.onSuccessFlow }
                    .collect {
                        ctx.publishOnMain {
                            onWebDavChange(null)
                            onSuccess()
                        }
                    }
            }
            launch {
                latest.filterNotNull()
                    .map { it.sideEffects }
                    .distinctUntilChanged()
                    .flatMapLatest { it.filePickerIntentFlow }
                    .collect { intent ->
                        ctx.publishOnMain {
                            handleFilePickerIntent(intent)
                        }
                    }
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
                .collect { input ->
                    val handlers = LinkedHashMap<String, (String) -> Unit>()
                    val snapshot = buildSnapshot(input, leContext, handlers)
                    ctx.publishOnMain {
                        latestTabs = input.inner.tabs
                        latestLocation = input.inner.location
                        latestDbFile = input.inner.dbFile
                        latestKeyFile = input.inner.keyFile
                        latestAction = input.action
                        fieldHandlers = handlers
                        onChange(snapshot)
                    }
                }
        }
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

    fun selectKeePassTab(key: String) {
        latestTabs?.items
            ?.firstOrNull { it.key == key }
            ?.onClick
            ?.invoke()
    }

    fun selectKeePassLocation(key: String) {
        latestLocation?.items
            ?.firstOrNull { it.type.toLocationKey() == key }
            ?.onClick
            ?.invoke()
    }

    fun pickKeePassDbFile() {
        latestDbFile?.onClick?.invoke()
    }

    fun clearKeePassDbFile() {
        latestDbFile?.onClear?.invoke()
    }

    fun pickKeePassKeyFile() {
        latestKeyFile?.onClick?.invoke()
    }

    fun clearKeePassKeyFile() {
        latestKeyFile?.onClear?.invoke()
    }

    fun setKeePassPassword(text: String) {
        fieldHandlers["keepass.password"]?.invoke(text)
    }

    fun submitKeePassLogin() {
        latestAction?.onClick?.invoke()
    }

    fun setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Unit)?) {
        onFilePickerRequest = handler
    }

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
        // The settings producer transmits the result and then pops itself; the
        // pop intent is dropped by the interceptor, so wrapping the transmitter
        // IS the dismissal signal for the Swift sheet.
        val wrappedTransmitter = object : RouteResultTransmitter<WebDavSettingsResult> {
            override fun invoke(p1: WebDavSettingsResult) {
                transmitter(p1)
                // Fired from the producer pipeline — hop to the main scope
                // like every other Swift-facing callback.
                ctx.scope.launch {
                    latestWebDavState = null
                    webDavJob?.cancel()
                    webDavJob = null
                    onWebDavChange(null)
                }
            }
        }
        ctx.scope.launch {
            webDavJob?.cancel()
            webDavJob = sessionScope.launch {
                val producerFlow = ctx.koin
                    .newHeadlessStateFlowScope("webdav_settings", this)
                    .webDavSettingsStateProducer(
                        route = route,
                        transmitter = wrappedTransmitter,
                        checkWebDavConnection = checkWebDavConnection,
                    )
                producerFlow
                    .throttleLatest()
                    .collect { state ->
                        val snapshot = WebDavSettingsSnapshot(
                            url = state.url.value,
                            username = state.username.value,
                            password = state.password.value,
                            errorKind = state.error?.name,
                            isTestingConnection = state.isTestingConnection,
                        )
                        ctx.publishOnMain {
                            latestWebDavState = state
                            onWebDavChange(snapshot)
                        }
                    }
            }
        }
    }

    fun setWebDavField(id: String, text: String) {
        val state = latestWebDavState ?: return
        when (id) {
            // The URL is written through the producer's own setter, which also
            // invalidates the remembered WebDAV browse root.
            "url" -> state.onUrlChange(text)
            "username" -> state.username.value = text
            "password" -> state.password.value = text
        }
    }

    fun submitWebDavSettings() {
        latestWebDavState?.onSave?.invoke()
    }

    fun testWebDavConnection() {
        latestWebDavState?.onTestConnection?.invoke()
    }

    fun cancelWebDavSettings() {
        latestWebDavState = null
        webDavJob?.cancel()
        webDavJob = null
    }
}
