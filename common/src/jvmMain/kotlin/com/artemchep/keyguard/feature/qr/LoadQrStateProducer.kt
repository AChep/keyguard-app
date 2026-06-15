package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.LeContext
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
actual fun produceLoadQrState(): Loadable<LoadQrState> = with(localDI().direct) {
    produceLoadQrState(
        context = instance(),
        windowCoroutineScope = instance(),
    )
}

@Composable
fun produceLoadQrState(
    context: LeContext,
    windowCoroutineScope: WindowCoroutineScope,
): Loadable<LoadQrState> = produceScreenState(
    key = "load_qr",
    initial = Loadable.Loading,
    args = arrayOf(
    ),
) {
    val onSuccessSink = EventFlow<String>()
    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()

    fun onSelect(uri: String) = io(uri)
        .effectMap(Dispatchers.Main) {
            scanBarcodeFromUri(
                context = context,
                uri = it,
            )
        }
        .handleErrorWith { e ->
            val msg = when (e) {
                is NotFoundException -> "Failed to find a barcode in the image"
                is FormatException -> "Failed to parse a barcode"
                is ChecksumException -> "Failed to parse a barcode"
                else -> return@handleErrorWith ioRaise(e)
            }
            ioRaise(RuntimeException(msg))
        }
        .effectMap { text ->
            onSuccessSink.emit(text)
        }
        .launchIn(screenScope)

    fun onClick() {
        val intent = FilePickerIntent.OpenDocument(
            mimeTypes = arrayOf(
                "image/png",
                "image/jpeg",
                "image/jpg",
            ),
        ) { info ->
            if (info != null) {
                val uri = info.uri.toString()
                onSelect(uri)
            }
        }
        filePickerIntentSink.emit(intent)
    }

    val content = LoadQrState.Content(
        onSelectFile = ::onClick,
    )
    val contentFlow = MutableStateFlow(content)

    val state = LoadQrState(
        contentFlow = contentFlow,
        onSuccessFlow = onSuccessSink,
        filePickerIntentFlow = filePickerIntentSink,
    )
    val success = Loadable.Ok(state)
    flowOf(success)
}
