package com.artemchep.keyguard.feature.dragout.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.feature.dragout.model.representations
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.ui.LocalComposeWindow
import com.artemchep.keyguard.ui.nativeWindowHandle

/**
 * Files need a native transport (an AppKit promise or an OLE virtual file); links use
 * plain AWT text. One drag takes a single transport, so files win when an item offers both.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun CompositionLocalConsumerModifierNode.prepareDragOut(payload: DragPayload): PreparedDragOut? {
    val files = payload.representations<DragRepresentation.File>().map { it.content }
    val urls = payload.representations<DragRepresentation.Url>().map { it.url }
    val offer = when {
        files.isEmpty() -> null
        CurrentPlatform is Platform.Desktop.MacOS -> {
            val window = currentValueOf(LocalComposeWindow).nativeWindowHandle
                ?: error("The native drag window is unavailable")
            MacFilePromiseDragOut(NativeDragFiles.Prepared(files), window)
        }

        CurrentPlatform is Platform.Desktop.Windows -> WindowsVirtualFileDragOut(files)
        // No file transport here; fall through to the links, if any.
        else -> null
    }
    return when {
        offer != null -> PreparedDragOut.Native(start = offer::start, close = offer::close)
        urls.isNotEmpty() -> PreparedDragOut.Compose(
            data = DragAndDropTransferData(
                transferable = DragAndDropTransferable(UrlTransferable(urls)),
                supportedActions = listOf(DragAndDropTransferAction.Copy),
            ),
            close = {},
        )

        else -> null
    }
}

/** AppKit promises a file by name alone; the drop's write waits for its preparation. */
internal actual fun CompositionLocalConsumerModifierNode.promiseDragOut(source: DragOutSource): PreparedDragOut? {
    val window = if (source.promisedFileName != null && CurrentPlatform is Platform.Desktop.MacOS) {
        currentValueOf(LocalComposeWindow).nativeWindowHandle
    } else {
        null
    } ?: return null
    val offer = MacFilePromiseDragOut(NativeDragFiles.Promised(source), window)
    return PreparedDragOut.Native(start = offer::start, close = offer::close)
}
