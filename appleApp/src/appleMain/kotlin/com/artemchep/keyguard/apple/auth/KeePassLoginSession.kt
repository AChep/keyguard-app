package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginState
import com.artemchep.keyguard.feature.auth.keepass.keePassLoginStateProducer
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.core.onFilePickerResult
import com.artemchep.keyguard.apple.core.toFilePickerRequest
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.RemoteSettingsSheets
import com.artemchep.keyguard.apple.core.S3SettingsSnapshot
import com.artemchep.keyguard.apple.core.WebDavSettingsSnapshot

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

    /** The WebDAV and S3 settings sheets the form opens for a remote database. */
    val settingsSheets = RemoteSettingsSheets(ctx)

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
        settingsSheets.cancel()
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
            // The producer navigates to the WebDAV or S3 settings route (wrapped
            // in a result receiver) when the user picks a remote location; claim
            // it and run the settings producer as a child of this observer, so a
            // lock mid-sheet tears it down.
            val sessionScope = this
            val interceptor = { intent: NavigationIntent ->
                settingsSheets.intercept(
                    intent = intent,
                    scope = sessionScope,
                    sessionKoin = state.sessionKoin,
                    onWebDavChange = onWebDavChange,
                    onS3Change = onS3Change,
                )
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
}
