package com.artemchep.keyguard.feature.filepicker

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.kodein.di.compose.rememberInstance

@Composable
actual fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
) {
    val state = remember {
        MutableStateFlow<FilePickerIntent<*>?>(null)
    }

    val context by rememberUpdatedState(LocalContext.current)
    val showMessage: ShowMessage by rememberInstance()

    fun takePersistableUriPermission(
        intent: FilePickerIntent<*>,
        uri: android.net.Uri,
    ) {
        val persistable = when (intent) {
            is FilePickerIntent.NewDocument -> intent.persistableUriPermission
            is FilePickerIntent.OpenDocument -> intent.persistableUriPermission
            is FilePickerIntent.OpenDirectory -> intent.persistableUriPermission
        }
        if (!persistable) {
            return
        }

        var flags = 0
        val readUriPermission = when (intent) {
            is FilePickerIntent.NewDocument -> intent.readUriPermission
            is FilePickerIntent.OpenDocument -> intent.readUriPermission
            is FilePickerIntent.OpenDirectory -> intent.readUriPermission
        }
        val writeUriPermission = when (intent) {
            is FilePickerIntent.NewDocument -> intent.writeUriPermission
            is FilePickerIntent.OpenDocument -> intent.writeUriPermission
            is FilePickerIntent.OpenDirectory -> intent.writeUriPermission
        }
        if (readUriPermission) flags =
            flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (writeUriPermission) flags =
            flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        flags.takeIf { f -> f != 0 }
            ?.also { f ->
                context.contentResolver
                    .takePersistableUriPermission(uri, f)
            }
    }

    //
    // Open document
    //

    val openDocumentLauncher = run {
        val contract = remember {
            ActivityResultContracts.OpenDocument()
        }
        rememberLauncherForActivityResult(contract = contract) { uri ->
            val intent = state.value as? FilePickerIntent.OpenDocument
                ?: run {
                    val message = ToastMessage(
                        title = "Failed to select a file",
                        text = "App does not have an active observer to handle the result correctly.",
                    )
                    showMessage.copy(message)
                    return@rememberLauncherForActivityResult
                }

            val info = uri?.let { uri ->
                takePersistableUriPermission(intent, uri)
                context.toFilePickerResult(uri)
            }
            intent.onResult(info)
        }
    }

    //
    // New document
    //

    val newDocumentLauncher = run {
        val contract = remember {
            ActivityResultContracts.CreateDocument()
        }
        rememberLauncherForActivityResult(contract = contract) { uri ->
            val intent = state.value as? FilePickerIntent.NewDocument
                ?: run {
                    val message = ToastMessage(
                        title = "Failed to select a file",
                        text = "App does not have an active observer to handle the result correctly.",
                    )
                    showMessage.copy(message)
                    return@rememberLauncherForActivityResult
                }

            val info = uri?.let { uri ->
                takePersistableUriPermission(intent, uri)
                context.toFilePickerResult(
                    uri = uri,
                    size = null,
                )
            }
            intent.onResult(info)
        }
    }

    //
    // Open directory
    //

    val openDirectoryLauncher = run {
        val contract = remember {
            ActivityResultContracts.OpenDocumentTree()
        }
        rememberLauncherForActivityResult(contract = contract) { uri ->
            val intent = state.value as? FilePickerIntent.OpenDirectory
                ?: run {
                    val message = ToastMessage(
                        title = "Failed to select a folder",
                        text = "App does not have an active observer to handle the result correctly.",
                    )
                    showMessage.copy(message)
                    return@rememberLauncherForActivityResult
                }

            val info = uri?.let { uri ->
                takePersistableUriPermission(intent, uri)
                context.toDirectoryPickerResult(uri)
            }
            intent.onResult(info)
        }
    }

    CollectedEffect(flow) { intent ->
        state.value = intent

        when (intent) {
            is FilePickerIntent.NewDocument -> {
                newDocumentLauncher.launch(intent.fileName)
            }

            is FilePickerIntent.OpenDocument -> {
                val mimeTypes = run {
                    // On Android that MIME type doesn't do anything, showing no
                    // available files if asked.
                    val isKeePass = FilePickerMime.KEEPASS_KDBX in intent.mimeTypes ||
                            FilePickerMime.KEEPASS_GENERIC in intent.mimeTypes
                    if (isKeePass) {
                        return@run FilePickerIntent.mimeTypesAll
                    }

                    intent.mimeTypes
                }
                openDocumentLauncher.launch(mimeTypes)
            }

            is FilePickerIntent.OpenDirectory -> {
                openDirectoryLauncher.launch(null)
            }
        }
    }
}
