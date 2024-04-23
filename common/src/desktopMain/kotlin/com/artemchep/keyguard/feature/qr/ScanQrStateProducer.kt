package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.net.URL
import javax.imageio.ImageIO

@Composable
fun produceScanQrState(): Loadable<ScanQrState> = with(localDI().direct) {
    produceScanQrState(
        windowCoroutineScope = instance(),
    )
}

@Composable
fun produceScanQrState(
    windowCoroutineScope: WindowCoroutineScope,
): Loadable<ScanQrState> = produceScreenState(
    key = "scan_qr",
    initial = Loadable.Loading,
    args = arrayOf(
    ),
) {
    val onSuccessSink = EventFlow<String>()
    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()

    fun onSelect(uri: String) = io(uri)
        .effectMap(Dispatchers.IO) {
            val url = URL(it)
            val image = ImageIO.read(url)
            requireNotNull(image) {
                "Failed to read a file as an image"
            }
        }
        .flatMap { image ->
            io(image)
                .effectMap(Dispatchers.Default) {
                    ScanQrUtil.qrDecodeFromImage(it)
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
        }
        .effectMap { text ->
            onSuccessSink.emit(text)
        }
        .launchIn(screenScope)

    fun onClick() {
        val intent = FilePickerIntent.OpenDocument(
            mimeTypes = arrayOf(
                "image/png",
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

    val content = ScanQrState.Content(
        onSelectFile = ::onClick,
    )
    val contentFlow = MutableStateFlow(content)

    val state = ScanQrState(
        contentFlow = contentFlow,
        onSuccessFlow = onSuccessSink,
        filePickerIntentFlow = filePickerIntentSink,
    )
    val success = Loadable.Ok(state)
    flowOf(success)
}
