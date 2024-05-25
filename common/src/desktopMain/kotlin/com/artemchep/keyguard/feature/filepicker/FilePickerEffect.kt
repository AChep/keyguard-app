package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.desktop.util.showFilePicker
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.LocalComposeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.kodein.di.compose.rememberInstance
import tv.wunderbox.nfd.FileDialog

@Composable
actual fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
) {
    val state = remember {
        MutableStateFlow<FilePickerIntent<*>?>(null)
    }

    val composeWindow by rememberUpdatedState(LocalComposeWindow.current)
    val context by rememberUpdatedState(LocalLeContext)
    val showMessage: ShowMessage by rememberInstance()

    CollectedEffect(flow) { intent ->
        state.value = intent

        when (intent) {
            is FilePickerIntent.OpenDocument -> {
                val mimeTypes = intent.mimeTypes

                val extensions = mimeTypes
                    .mapNotNull { mimeType ->
                        when {
                            mimeType == "text/plain" -> "txt"
                            mimeType == "text/wordlist" -> "wordlist"
                            mimeType == "image/png" -> "png"
                            mimeType == "image/jpg" -> "jpg"
                            else -> null
                        }
                    }
                val filters = if (extensions.isNotEmpty()) {
                    val title = textResource(Res.string.select_file, context)
                    listOf(
                        FileDialog.Filter(
                            title = title,
                            extensions = extensions,
                        ),
                    )
                } else {
                    emptyList()
                }
                showFilePicker(
                    composeWindow = composeWindow,
                    filters = filters,
                ) { file ->
                    val info = FilePickerIntent.OpenDocument.Ifo(
                        uri = leParseUri(file),
                        name = file.name,
                        size = file.length(),
                    )
                    intent.onResult(info)
                }
            }
        }
    }
}
