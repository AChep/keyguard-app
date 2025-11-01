package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent.Companion.mimeTypesKeePass
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.create_database
import com.artemchep.keyguard.res.open_database
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val DEFAULT_DATABASE_NAME = "MyKeyguardDatabase.kdbx"

private const val MODE_OPEN = "open"
private const val MODE_NEW = "new"

@Composable
fun produceKeePassLoginScreenState(
): Loadable<KeePassLoginState> = with(localDI().direct) {
    produceKeePassLoginScreenState(
        addKeepassAccount = instance(),
    )
}

private inline val defaultPassword get() = ""

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun produceKeePassLoginScreenState(
    addKeepassAccount: AddKeePassAccount,
): Loadable<KeePassLoginState> = produceScreenState(
    initial = Loadable.Loading,
    key = "keepasslogin",
    args = arrayOf(
        addKeepassAccount,
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

    val dbFileSink = mutablePersistedFlow<KeePassLoginState.FileItem.File?>("db_file") {
        null
    }
    val keyFileSink = mutablePersistedFlow<KeePassLoginState.FileItem.File?>("key_file") {
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
    )

    fun onSubmit(
        mode: String,
        dbFile: KeePassLoginState.FileItem.File,
        keyFile: KeePassLoginState.FileItem.File?,
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
            keyUri = keyFile?.uri,
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
        val onFileSelected: (FilePickerResult?) -> Unit = { info ->
            if (info != null) {
                val file = info.toFile()
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

    val dbFileState = dbFileSink
        .map { file ->
            val onClear = if (file != null) {
                // lambda
                {
                    dbFileSink.value = null
                }
            } else {
                null
            }
            KeePassLoginState.FileItem(
                onClick = ::onSelectDbFile,
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
        passwordValidatedFlow,
    ) { mode, dbFile, keyFile, passwordValidated ->
        // The mode and database file are required
        // for the login process.
        if (mode == null || dbFile == null) {
            return@combine null
        }

        // The password should be valid.
        if (passwordValidated !is Validated.Success) {
            return@combine null
        }

        KeePassLoginState.Action(
            onClick = {
                onSubmit(
                    mode = mode,
                    dbFile = dbFile,
                    keyFile = keyFile,
                    password = passwordValidated.model,
                )
            },
        )
    }
        .stateIn(screenScope)

    flowOf(
        Loadable.Ok(
            KeePassLoginState(
                sideEffects = sideEffects,
                dbFileState = dbFileState,
                keyFileState = keyFileState,
                tabsState = tabsState,
                password = passwordFlow,
                actionState = actionState,
            )
        )
    )
}
