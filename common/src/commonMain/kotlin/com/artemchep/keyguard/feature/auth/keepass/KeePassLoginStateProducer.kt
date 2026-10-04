package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.webdav.parseWebDavKeePassFileUrl
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent.Companion.mimeTypesKeePass
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.s3.S3SettingsResult
import com.artemchep.keyguard.feature.s3.S3SettingsRoute
import com.artemchep.keyguard.feature.s3.s3LocationUri
import com.artemchep.keyguard.feature.webdav.WebDavSettingsRoute
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.create_database
import com.artemchep.keyguard.res.database_location_local
import com.artemchep.keyguard.res.database_location_s3
import com.artemchep.keyguard.res.database_location_webdav
import com.artemchep.keyguard.res.open_database
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.currentKoinScope

private const val DEFAULT_DATABASE_NAME = "MyKeyguardDatabase.kdbx"

private const val MODE_OPEN = "open"
private const val MODE_NEW = "new"
private const val LOCATION_LOCAL = "local"
private const val LOCATION_WEBDAV = "webdav"
private const val LOCATION_S3 = "s3"
private const val DEFAULT_SCREEN_KEY = "keepasslogin"

internal fun createKeePassLoginAction(
    mode: String?,
    dbFile: KeePassLoginState.FileItem.File?,
    keyFile: KeePassLoginState.FileItem.File?,
    webDav: KeePassLoginState.WebDav?,
    s3: KeePassLoginState.S3? = null,
    passwordValidated: Validated<String>,
    onSubmit: (
        mode: String,
        dbFile: KeePassLoginState.FileItem.File,
        keyFile: KeePassLoginState.FileItem.File?,
        webDav: KeePassLoginState.WebDav?,
        s3: KeePassLoginState.S3?,
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
                s3,
                password,
            )
        },
    )
}

internal fun KeePassLoginState.WebDav.toKeePassLoginFile() =
    KeePassLoginState.FileItem.File(
        uri = url,
        name = parseWebDavKeePassFileUrl(url).path,
        size = null,
    )

internal fun KeePassLoginState.S3.toKeePassLoginFile() =
    KeePassLoginState.FileItem.File(
        uri = s3LocationUri(bucket, key),
        name = key.substringAfterLast('/'),
        size = null,
    )

internal fun KeePassLoginState.S3.toS3Location() = S3Location.Object(
    bucket = S3Bucket(
        endpoint = endpoint,
        region = region,
        name = bucket,
        pathStyle = pathStyle,
    ),
    accessKey = S3AccessKey(
        accessKeyId = accessKeyId,
        secretAccessKey = Password(secretAccessKey),
    ),
    key = key,
)

internal fun S3Location.Object.toKeePassLoginS3() = KeePassLoginState.S3(
    endpoint = bucket.endpoint,
    region = bucket.region,
    bucket = bucket.name,
    key = key,
    accessKeyId = accessKey.accessKeyId,
    secretAccessKey = accessKey.secretAccessKey.value,
    pathStyle = bucket.pathStyle,
)

internal fun createKeePassLoginState(
    sideEffects: KeePassLoginState.SideEffect,
    dbFileState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.FileItem>,
    keyFileState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.FileItem>,
    databaseLocationState: kotlinx.coroutines.flow.StateFlow<KeePassLoginState.DatabaseLocation>,
    password: kotlinx.coroutines.flow.StateFlow<TextFieldModel>,
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
): Loadable<KeePassLoginState> = with(currentKoinScope()) {
    produceKeePassLoginScreenState(
        addKeepassAccount = get(),
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
    keePassLoginStateProducer(
        addKeepassAccount = addKeepassAccount,
    )
}

// Keep the state flows and their session-scoped callbacks in one lifecycle scope.
@Suppress("CyclomaticComplexMethod", "LongMethod")
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun RememberStateFlowScope.keePassLoginStateProducer(
    addKeepassAccount: AddKeePassAccount,
): Flow<Loadable<KeePassLoginState>> {
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
    val s3Sink = mutablePersistedFlow<KeePassLoginState.S3?>("s3") {
        null
    }

    val passwordHandle = textFieldHandle("password", initial = defaultPassword)
    val passwordPairFlow = combine(
        passwordHandle.sink,
        keyFileSink,
    ) { cell, keyFile ->
        // A database protected by a key file alone
        // does not need a password.
        val minLength = if (keyFile != null) 0 else 1
        cell to validatedPassword(
            password = cell.text,
            minLength = minLength,
        )
    }
        .shareInScreenScope()
    val passwordValidatedFlow = passwordPairFlow
        .map { it.second }
    val passwordFlow = passwordPairFlow
        .map { (cell, passwordValidated) ->
            TextFieldModel(
                text = cell.text,
                textRevision = cell.revision,
                error = (passwordValidated as? Validated.Failure)?.error,
                onChange = passwordHandle::onChange,
                onSetText = passwordHandle::setText,
            )
        }
        .stateIn(screenScope)

    fun FilePickerResult.toFile() = KeePassLoginState.FileItem.File(
        uri = uri.toString(),
        name = name,
        size = size,
        accessToken = accessToken,
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
        s3Sink.value = null
        dbFileSink.value = webDav.toKeePassLoginFile()
    }

    fun onS3LocationSelected(
        result: S3SettingsResult,
    ) {
        val location = result.location as? S3Location.Object
            ?: return
        val s3 = location.toKeePassLoginS3()
        databaseLocationSink.value = LOCATION_S3
        s3Sink.value = s3
        webDavSink.value = null
        dbFileSink.value = s3.toKeePassLoginFile()
    }

    fun onSelectS3Location() {
        val s3 = s3Sink.value
        val route = registerRouteResultReceiver(
            route = S3SettingsRoute(
                args = S3SettingsRoute.Args(
                    endpoint = s3?.endpoint.orEmpty(),
                    region = s3?.region.orEmpty(),
                    bucket = s3?.bucket.orEmpty(),
                    path = s3?.key.orEmpty(),
                    accessKeyId = s3?.accessKeyId.orEmpty(),
                    secretAccessKey = s3?.secretAccessKey.orEmpty(),
                    pathStyle = s3?.pathStyle ?: true,
                    purpose = S3SettingsRoute.Purpose.KeePassDatabase,
                    keePassMode = when (tabSink.value) {
                        MODE_NEW -> S3SettingsRoute.KeePassMode.Create
                        else -> S3SettingsRoute.KeePassMode.Open
                    },
                ),
            ),
        ) { result ->
            onS3LocationSelected(result)
        }
        navigate(NavigationIntent.NavigateToRoute(route))
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
                    keePassMode = when (tabSink.value) {
                        MODE_NEW -> WebDavSettingsRoute.KeePassMode.Create
                        else -> WebDavSettingsRoute.KeePassMode.Open
                    },
                ),
            ),
        ) { result ->
            onWebDavLocationSelected(result)
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }

    fun onSelectRemoteLocation(location: String) {
        when (location) {
            LOCATION_WEBDAV -> onSelectWebDavLocation()
            LOCATION_S3 -> onSelectS3Location()
        }
    }

    fun onSelectLocalLocation() {
        databaseLocationSink.value = LOCATION_LOCAL
        webDavSink.value = null
        s3Sink.value = null
        dbFileSink.value = null
    }

    fun onSubmit(
        mode: String,
        dbFile: KeePassLoginState.FileItem.File,
        keyFile: KeePassLoginState.FileItem.File?,
        webDav: KeePassLoginState.WebDav?,
        s3: KeePassLoginState.S3?,
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
            s3 = s3?.toS3Location(),
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
                s3Sink.value = null
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
        val location = databaseLocationSink.value
        if (location == LOCATION_WEBDAV || location == LOCATION_S3) {
            tabSink.value = mode
            passwordHandle.setText("")
            keyFileSink.value = null
            val remote = if (location == LOCATION_WEBDAV) webDavSink.value else s3Sink.value
            if (remote == null) {
                onSelectRemoteLocation(location)
            }
            return
        }

        val onFileSelected: (FilePickerResult?) -> Unit = { info ->
            if (info != null) {
                val file = info.toFile()
                databaseLocationSink.value = LOCATION_LOCAL
                webDavSink.value = null
                s3Sink.value = null
                dbFileSink.value = file

                // Also change the
                // current mode and reset password.
                tabSink.value = mode
                // Command path: bumps the revision so the field's edit
                // buffer adopts the cleared text.
                passwordHandle.setText("")
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
                LOCATION_S3 -> KeePassLoginState.DatabaseLocation.Type.S3
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
                KeePassLoginState.DatabaseLocation.Item(
                    type = KeePassLoginState.DatabaseLocation.Type.S3,
                    title = TextHolder.Res(Res.string.database_location_s3),
                    checked = type == KeePassLoginState.DatabaseLocation.Type.S3,
                    onClick = ::onSelectS3Location,
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
                    when (databaseLocationSink.value) {
                        LOCATION_WEBDAV -> webDavSink.value = null
                        LOCATION_S3 -> s3Sink.value = null
                    }
                }
            } else {
                null
            }
            KeePassLoginState.FileItem(
                onClick = {
                    when (val location = databaseLocationSink.value) {
                        LOCATION_WEBDAV,
                        LOCATION_S3,
                        -> onSelectRemoteLocation(location)

                        else -> onSelectDbFile()
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

    // A typed combine takes at most five flows.
    val remoteFlow = combine(
        webDavSink,
        s3Sink,
    ) { webDav, s3 -> webDav to s3 }
    val actionState = combine(
        tabSink,
        dbFileSink,
        keyFileSink,
        remoteFlow,
        passwordValidatedFlow,
    ) { mode, dbFile, keyFile, (webDav, s3), passwordValidated ->
        createKeePassLoginAction(
            mode = mode,
            dbFile = dbFile,
            keyFile = keyFile,
            webDav = webDav,
            s3 = s3,
            passwordValidated = passwordValidated,
            onSubmit = { actionMode, actionDbFile, actionKeyFile, actionWebDav, actionS3, actionPassword ->
                onSubmit(
                    mode = actionMode,
                    dbFile = actionDbFile,
                    keyFile = actionKeyFile,
                    webDav = actionWebDav,
                    s3 = actionS3,
                    password = actionPassword,
                )
            },
        )
    }
        .stateIn(screenScope)

    return actionExecutor.isExecutingFlow.map { taskIsExecuting ->
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
