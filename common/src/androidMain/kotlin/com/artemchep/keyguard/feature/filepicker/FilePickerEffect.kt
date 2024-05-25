package com.artemchep.keyguard.feature.filepicker

import android.content.Context
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

            val info = uri?.let {
                FilePickerIntent.OpenDocument.Ifo(
                    uri = LeUriImpl(it),
                    name = getFileName(context, it),
                    size = getFileSize(context, it),
                )
            }
            intent.onResult(info)
        }
    }

    CollectedEffect(flow) { intent ->
        state.value = intent

        when (intent) {
            is FilePickerIntent.OpenDocument -> {
                val mimeTypes = intent.mimeTypes
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
