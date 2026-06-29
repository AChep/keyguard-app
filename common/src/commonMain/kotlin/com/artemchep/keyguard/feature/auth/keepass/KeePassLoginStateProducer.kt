package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent.Companion.mimeTypesKeePass
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.webdav.WebDavSettingsRoute
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.create_database
import com.artemchep.keyguard.res.database_location_local
import com.artemchep.keyguard.res.database_location_webdav
import com.artemchep.keyguard.res.open_database
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val DEFAULT_DATABASE_NAME = "MyKeyguardDatabase.kdbx"

private const val MODE_OPEN = "open"
private const val MODE_NEW = "new"
private const val LOCATION_LOCAL = "local"
private const val LOCATION_WEBDAV = "webdav"
private const val DEFAULT_SCREEN_KEY = "keepasslogin"

internal fun createKeePassLoginAction(
    mode: String?,
    dbFile: KeePassLoginState.FileItem.File?,
    keyFile: KeePassLoginState.FileItem.File?,
    webDav: KeePassLoginState.WebDav?,
    passwordValidated: Validated<String>,
    onSubmit: (
        mode: String,
        dbFile: KeePassLoginState.FileItem.File,
        keyFile: KeePassLoginState.FileItem.File?,
        webDav: KeePassLoginState.WebDav?,
        password: String,
    ) -> Unit,
): KeePassLoginState.Action? {
    if (mode == null || dbFile == null) {
        return null
    }
    val password = (passwordValidated as? Validated.Success)
        ?.model
        ?: return null
    return KeePassLoginState.Action(
        onClick = {
            onSubmit(
                mode,
                dbFile,
                keyFile,
                webDav,
                password,
            )
        },
    )
}

internal fun createKeePassLoginState(
    sideEffects: KeePassLoginState.SideEffect,
    dbFileState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.FileItem>,
    keyFileState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.FileItem>,
    databaseLocationState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.DatabaseLocation>,
    password: kotlinx.coroutines.flow.StateFlow<TextFieldModel2>,
    actionState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.Action?>,
    tabsState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.Tabs>,
    isLoading: Boolean,
): KeePassLoginState = KeePassLoginState(
    sideEffects = sideEffects,
    dbFileState = dbFileState,
    keyFileState = keyFileState,
    databaseLocationState = databaseLocationState,
    tabsState = tabsState,
    password = password,
    actionState = actionState,
    isLoading = isLoading,
)

@Composable
fun produceKeePassLoginScreenState(
    screenKey: String = DEFAULT_SCREEN_KEY,
): Loadable<KeePassLoginState> = with(localDI().direct) {
    produceKeePassLoginScreenState(
        addKeepassAccount = instance(),
        screenKey = screenKey,
    )
}

private inline val defaultPassword get() = ""

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun produceKeePassLoginScreenState(
    addKeepassAccount: AddKeePassAccount,
    screenKey: String = DEFAULT_SCREEN_KEY,
): Loadable<KeePassLoginState> = produceScreenState(
    initial = Loadable.Loading,
    key = screenKey,
    args = arrayOf(
        addKeepassAccount,
        screenKey,
    ),
) {
    val onSuccessFlow = EventFlow<Unit>()
    val onErrorFlow = EventFlow<BitwardenLoginEvent.Error>()

    val actionExecutor = screenExecutor()

    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()

    val sideEffects = KeePassLoginState.SideEffect(
        filePickerIntentFlow = filePickerIntentSink,
        onSuccessFlow = onSuccessFlow,
        onErrorFlow = onErrorFlow,
    )

    val tabSink = mutablePersistedFlow<String?>("mode") {
        null
    }
    val databaseLocationSink = mutablePersistedFlow("database_location") {
        LOCATION_LOCAL
    }

    val dbFileSink = mutablePersistedFlow<KeePassLoginState.FileItem.File?>("db_file") {
        null
    }
    val keyFileSink = mutablePersistedFlow<KeePassLoginState.FileItem.File?>("key_file") {
        null
    }
    val webDavSink = mutablePersistedFlow<KeePassLoginState.WebDav?>("webdav") {
        null
    }

    val passwordSink = mutablePersistedFlow("password") {
        defaultPassword
    }
    val passwordState = mutableComposeState(passwordSink)
    val passwordValidatedFlow = passwordSink
        .validatedPassword(this, minLength = 1)
        .shareInScreenScope()
    val passwordFlow = passwordValidatedFlow
        .map { passwordValidated ->
            TextFieldModel2(
                state = passwordState,
                text = passwordValidated.model,
                error = (passwordValidated as? Validated.Failure)?.error,
                onChange = passwordState::value::set,
            )
        }
        .stateIn(screenScope)

    fun FilePickerResult.toFile() = KeePassLoginState.FileItem.File(
        uri = uri.toString(),
        name = name,
        size = size,
        accessToken = accessToken,
    )

    fun KeePassLoginState.WebDav.toFile() = KeePassLoginState.FileItem.File(
        uri = url,
        name = url.substringBefore('#')
            .substringBefore('?')
            .substringAfterLast('/'),
        size = null,
    )

    fun onWebDavLocationSelected(
        result: com.artemchep.keyguard.feature.webdav.WebDavSettingsResult,
    ) {
        val webDav = KeePassLoginState.WebDav(
            url = result.url,
            username = result.username,
            password = result.password,
        )
        databaseLocationSink.value = LOCATION_WEBDAV
        webDavSink.value = webDav
        dbFileSink.value = webDav.toFile()
    }

    fun onSelectWebDavLocation() {
        val webDav = webDavSink.value
        val route = registerRouteResultReceiver(
            route = WebDavSettingsRoute(
                args = WebDavSettingsRoute.Args(
                    url = webDav?.url.orEmpty(),
                    username = webDav?.username.orEmpty(),
                    password = webDav?.password.orEmpty(),
                    purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
                ),
            ),
        ) { result ->
            onWebDavLocationSelected(result)
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    fun onSelectLocalLocation() {
        databaseLocationSink.value = LOCATION_LOCAL
        webDavSink.value = null
        dbFileSink.value = null
    }

    fun onSubmit(
        mode: String,
        dbFile: KeePassLoginState.FileItem.File,
        keyFile: KeePassLoginState.FileItem.File?,
        webDav: KeePassLoginState.WebDav?,
        password: String,
    ) {
        val paramsMode = when (mode) {
            MODE_OPEN -> AddKeePassAccountParams.Mode.Open
            MODE_NEW -> AddKeePassAccountParams.Mode.New(
                allowOverwrite = false,
            )
            else -> return
        }
        val params = AddKeePassAccountParams(
            mode = paramsMode,
            dbUri = dbFile.uri,
            dbFileName = dbFile.name.orEmpty(),
            webDav = webDav?.let {
                WebDavLocation.File(
                    url = it.url,
                    credentials = WebDavCredentials.of(
                        username = it.username,
                        password = it.password,
                    ),
                )
            },
            dbAccessToken = dbFile.accessToken,
            keyUri = keyFile?.uri,
            keyAccessToken = keyFile?.accessToken,
            password = password,
        )
        val io = addKeepassAccount(params)
            .effectTap {
                onSuccessFlow.emit(Unit)
            }
        actionExecutor.execute(io)
    }

    fun onSelectDbFile() {
        val intent = FilePickerIntent.OpenDocument(
            mimeTypes = mimeTypesKeePass,
            readUriPermission = true,
            writeUriPermission = true,
            persistableUriPermission = true,
        ) { info ->
            if (info != null) {
                val file = info.toFile()
                databaseLocationSink.value = LOCATION_LOCAL
                webDavSink.value = null
                dbFileSink.value = file
            }
        }
        filePickerIntentSink.emit(intent)
    }

    fun onSelectKeyFile() {
        val intent = FilePickerIntent.OpenDocument(
            readUriPermission = true,
            persistableUriPermission = true,
        ) { info ->
            if (info != null) {
                val file = info.toFile()
                keyFileSink.value = file
            }
        }
        filePickerIntentSink.emit(intent)
    }

    fun onSelectMode(mode: String) {
        if (databaseLocationSink.value == LOCATION_WEBDAV) {
            tabSink.value = mode
            passwordSink.value = ""
            keyFileSink.value = null
            if (webDavSink.value == null) {
                onSelectWebDavLocation()
            }
            return
        }

        val onFileSelected: (FilePickerResult?) -> Unit = { info ->
            if (info != null) {
                val file = info.toFile()
                databaseLocationSink.value = LOCATION_LOCAL
                webDavSink.value = null
                dbFileSink.value = file

                // Also change the
                // current mode and reset password.
                tabSink.value = mode
                passwordSink.value = ""
                keyFileSink.value = null
            }
        }

        val intent = when (mode) {
            MODE_OPEN -> {
                FilePickerIntent.OpenDocument(
                    mimeTypes = mimeTypesKeePass,
                    readUriPermission = true,
                    writeUriPermission = true,
                    persistableUriPermission = true,
                    onResult = onFileSelected,
                )
            }
            MODE_NEW -> {
                FilePickerIntent.NewDocument(
                    fileName = DEFAULT_DATABASE_NAME,
                    readUriPermission = true,
                    writeUriPermission = true,
                    persistableUriPermission = true,
                    onResult = onFileSelected,
                )
            }
            else -> {
                // Should never happen
                return
            }
        }
        filePickerIntentSink.emit(intent)
    }

    val tabs = listOf(
        KeePassLoginType(
            key = MODE_OPEN,
            title = TextHolder.Res(Res.string.open_database),
            checked = false,
            onClick = ::onSelectMode
                .partially1(MODE_OPEN),
        ),
        KeePassLoginType(
            key = MODE_NEW,
            title = TextHolder.Res(Res.string.create_database),
            checked = false,
            onClick = ::onSelectMode
                .partially1(MODE_NEW),
        ),
    )
    val tabsState = tabSink
        .map { mode ->
            val tabs = tabs
                .map { tab ->
                    tab.copy(
                        checked = tab.key == mode,
                    )
                }
                .toImmutableList()
            KeePassLoginState.Tabs(
                items = tabs,
            )
        }
        .stateIn(screenScope)

    val databaseLocationState = databaseLocationSink
        .map { location ->
            val type = when (location) {
                LOCATION_WEBDAV -> KeePassLoginState.DatabaseLocation.Type.WebDav
                else -> KeePassLoginState.DatabaseLocation.Type.Local
            }
            val items = listOf(
                KeePassLoginState.DatabaseLocation.Item(
                    type = KeePassLoginState.DatabaseLocation.Type.Local,
                    title = TextHolder.Res(Res.string.database_location_local),
                    checked = type == KeePassLoginState.DatabaseLocation.Type.Local,
                    onClick = ::onSelectLocalLocation,
                ),
                KeePassLoginState.DatabaseLocation.Item(
                    type = KeePassLoginState.DatabaseLocation.Type.WebDav,
                    title = TextHolder.Res(Res.string.database_location_webdav),
                    checked = type == KeePassLoginState.DatabaseLocation.Type.WebDav,
                    onClick = ::onSelectWebDavLocation,
                ),
            ).toImmutableList()
            KeePassLoginState.DatabaseLocation(
                type = type,
                items = items,
            )
        }
        .stateIn(screenScope)

    val dbFileState = dbFileSink
        .map { file ->
            val onClear = if (file != null) {
                // lambda
                {
                    dbFileSink.value = null
                    if (databaseLocationSink.value == LOCATION_WEBDAV) {
                        webDavSink.value = null
                    }
                }
            } else {
                null
            }
            KeePassLoginState.FileItem(
                onClick = {
                    if (databaseLocationSink.value == LOCATION_WEBDAV) {
                        onSelectWebDavLocation()
                    } else {
                        onSelectDbFile()
                    }
                },
                onClear = onClear,
                file = file,
            )
        }
        .stateIn(screenScope)

    val keyFileState = keyFileSink
        .map { file ->
            val onClear = if (file != null) {
                // lambda
                {
                    keyFileSink.value = null
                }
            } else {
                null
            }
            KeePassLoginState.FileItem(
                onClick = ::onSelectKeyFile,
                onClear = onClear,
                file = file,
            )
        }
        .stateIn(screenScope)

    val actionState = combine(
        tabSink,
        dbFileSink,
        keyFileSink,
        webDavSink,
        passwordValidatedFlow,
    ) { mode, dbFile, keyFile, webDav, passwordValidated ->
        createKeePassLoginAction(
            mode = mode,
            dbFile = dbFile,
            keyFile = keyFile,
            webDav = webDav,
            passwordValidated = passwordValidated,
            onSubmit = { actionMode, actionDbFile, actionKeyFile, actionWebDav, actionPassword ->
                onSubmit(
                    mode = actionMode,
                    dbFile = actionDbFile,
                    keyFile = actionKeyFile,
                    webDav = actionWebDav,
                    password = actionPassword,
                )
            },
        )
    }
        .stateIn(screenScope)

    actionExecutor.isExecutingFlow.map { taskIsExecuting ->
        Loadable.Ok(
            createKeePassLoginState(
                sideEffects = sideEffects,
                dbFileState = dbFileState,
                keyFileState = keyFileState,
                databaseLocationState = databaseLocationState,
                tabsState = tabsState,
                password = passwordFlow,
                actionState = actionState,
                isLoading = taskIsExecuting,
            ),
        )
    }
}
