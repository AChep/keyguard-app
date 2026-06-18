package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.desktop.util.showDirectoryPicker
import com.artemchep.keyguard.desktop.util.showFilePicker
import com.artemchep.keyguard.desktop.util.showFileSaver
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.ui.CollectedEffect
import io.github.vinceglb.filekit.dialogs.FileKitType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
actual fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
) {
    val state = remember {
        MutableStateFlow<FilePickerIntent<*>?>(null)
    }

    CollectedEffect(flow) { intent ->
        state.value = intent

        when (intent) {
            is FilePickerIntent.NewDocument -> {
                showFileSaver(
                    suggestedName = intent.fileName,
                    extension = null,
                ) { file ->
                    val info = FilePickerResult(
                        uri = leParseUri(file.toURI().toString()),
                        name = file.name,
                        size = null,
                    )
                    intent.onResult(info)
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
