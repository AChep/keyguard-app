package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.desktop.util.showDirectoryPicker
import com.artemchep.keyguard.desktop.util.showFilePicker
import com.artemchep.keyguard.desktop.util.showFileSaver
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_open_app_for
import com.artemchep.keyguard.ui.CollectedEffect
import io.github.vinceglb.filekit.dialogs.FileKitType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import java.io.File

@Composable
actual fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
) {
    val state = remember {
        MutableStateFlow<FilePickerIntent<*>?>(null)
    }
    val showMessage: ShowMessage = koinInject()

    CollectedEffect(flow) { intent ->
        state.value = intent

        when (intent) {
            is FilePickerIntent.NewDocument -> {
                handleNewDocument(intent, showMessage) {
                    showFileSaver(suggestedName = intent.fileName, extension = null)
                }
            }
            is FilePickerIntent.OpenDocument -> {
                val extensions = mimeTypesToExtensions(intent.mimeTypes.asIterable())
                val type = FileKitType.File(extensions = extensions)
                showFilePicker(
                    type = type,
                ) { file ->
                    val info = FilePickerResult(
                        uri = leParseUri(file.toURI().toString()),
                        name = file.name,
                        size = file.length(),
                    )
                    intent.onResult(info)
                }
            }
            is FilePickerIntent.OpenDirectory -> {
                showDirectoryPicker { file ->
                    val info = FilePickerResult(
                        uri = leParseUri(file.toURI().toString()),
                        name = file.name,
                        size = null,
                    )
                    intent.onResult(info)
                }
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught") // Picker boundary; cancellation and fatal failures are rethrown.
internal suspend fun handleNewDocument(
    intent: FilePickerIntent.NewDocument,
    showMessage: ShowMessage,
    selectFile: suspend () -> File?,
) {
    val result = try {
        selectFile()?.let { file ->
            FilePickerResult(uri = leParseUri(file.toURI().toString()), name = file.name, size = null)
        }
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        recordException(e)
        showMessage.copy(
            ToastMessage(type = ToastMessage.Type.ERROR, title = getString(Res.string.error_failed_open_app_for)),
        )
        null
    }
    intent.onResult(result)
}

internal fun mimeTypesToExtensions(mimeTypes: Iterable<String>): Set<String>? {
    return mimeTypes
        .flatMap { mimeType ->
            when (mimeType) {
                "text/plain" -> listOf("txt")
                "text/wordlist" -> listOf("wordlist")
                FilePickerMime.KEEPASS_KDBX,
                FilePickerMime.KEEPASS_GENERIC,
                    -> listOf("kdbx")

                "image/png" -> listOf("png")
                "image/jpeg",
                "image/jpg",
                    -> listOf("jpg", "jpeg")

                else -> emptyList()
            }
        }
        .toSet()
        .takeIf { it.isNotEmpty() }
}
