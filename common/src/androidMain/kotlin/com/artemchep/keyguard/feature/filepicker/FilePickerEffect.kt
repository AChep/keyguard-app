package com.artemchep.keyguard.feature.filepicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.platform.LeUriImpl
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
                // Take persistable URI permission,
                // if requested.
                if (intent.persistableUriPermission) run {
                    var flags = 0
                    if (intent.readUriPermission) flags =
                        flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (intent.writeUriPermission) flags =
                        flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    flags.takeIf { f -> f != 0 }
                        ?.also { f ->
                            context.contentResolver
                                .takePersistableUriPermission(uri, f)
                        }
                }
                FilePickerResult(
                    uri = LeUriImpl(uri),
                    name = getFileName(context, uri),
                    size = getFileSize(context, uri),
                )
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
                // Take persistable URI permission,
                // if requested.
                if (intent.persistableUriPermission) run {
                    var flags = 0
                    if (intent.readUriPermission) flags =
                        flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (intent.writeUriPermission) flags =
                        flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    flags.takeIf { f -> f != 0 }
                        ?.also { f ->
                            context.contentResolver
                                .takePersistableUriPermission(uri, f)
                        }
                }
                FilePickerResult(
                    uri = LeUriImpl(uri),
                    name = getFileName(context, uri),
                    size = null,
                )
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
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context
            .contentResolver
            .query(uri, null, null, null, null)
        cursor?.use { x ->
            if (x.moveToFirst()) {
                val index = x.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = x.getString(index)
                }
            }
        }
    }
    if (result == null) {
        val r = uri.path.orEmpty()
        val cut = r.lastIndexOf('/')
        if (cut != -1) {
            result = r.substring(cut + 1)
        }
    }
    return result
}

private fun getFileSize(context: Context, uri: Uri): Long? {
    var result: Long? = null
    if (uri.scheme == "content") {
        val cursor = context
            .contentResolver
            .query(uri, null, null, null, null)
        cursor?.use { x ->
            if (x.moveToFirst()) {
                val index = x.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) {
                    result = x.getLong(index)
                }
            }
        }
    }
    return result
}
