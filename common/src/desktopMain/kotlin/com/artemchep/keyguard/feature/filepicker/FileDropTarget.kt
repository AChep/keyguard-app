package com.artemchep.keyguard.feature.filepicker

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import com.artemchep.keyguard.platform.leParseUri
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onFileDrop: (FilePickerResult) -> Unit,
): Modifier {
    val target = remember {
        DesktopFileDropTarget()
    }

    SideEffect {
        target.onDragActiveChange = onDragActiveChange
        target.onFileDrop = onFileDrop
        if (!enabled) {
            target.reset()
        }
    }

    if (!enabled) {
        return this
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = target::shouldStartDragAndDrop,
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.fileDragMonitor(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier {
    val target = remember {
        DesktopFileDragMonitor()
    }

    SideEffect {
        target.onDragActiveChange = onDragActiveChange
        if (!enabled) {
            target.reset()
        }
    }

    if (!enabled) {
        return this
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = target::shouldStartDragAndDrop,
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun droppedFileResult(
    dragData: DragData,
): FilePickerResult? = when (dragData) {
    is DragData.FilesList -> runCatching {
        dragData.readFiles()
    }.getOrNull()?.let(::droppedFileResult)

    else -> null
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun isFileDragData(
    dragData: DragData,
): Boolean = dragData is DragData.FilesList

internal fun droppedFileResult(
    fileUris: List<String>,
): FilePickerResult? = fileUris.firstNotNullOfOrNull(::droppedFileResult)

private fun droppedFileResult(
    fileUri: String,
): FilePickerResult? = runCatching {
    val uri = URI(fileUri)
        .takeIf { it.scheme.equals("file", ignoreCase = true) }
        ?: return@runCatching null
    val path = Path.of(uri)
        .takeIf(Files::isRegularFile)
        ?: return@runCatching null
    FilePickerResult(
        uri = leParseUri(path.toUri().toString()),
        name = path.name,
        size = path.fileSize(),
    )
}.getOrNull()

@OptIn(ExperimentalComposeUiApi::class)
private class DesktopFileDropTarget : DragAndDropTarget {
    var onDragActiveChange: ((Boolean) -> Unit)? = null
    var onFileDrop: ((FilePickerResult) -> Unit)? = null

    fun reset() {
        onDragActiveChange?.invoke(false)
    }

    fun shouldStartDragAndDrop(
        event: DragAndDropEvent,
    ): Boolean = isFileDragData(event.dragData())

    override fun onEntered(event: DragAndDropEvent) {
        updateDragActive(event)
    }

    override fun onMoved(event: DragAndDropEvent) {
        updateDragActive(event)
    }

    override fun onExited(event: DragAndDropEvent) {
        reset()
    }

    override fun onEnded(event: DragAndDropEvent) {
        reset()
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val file = droppedFileResult(event.dragData())
            ?: return false

        onDragActiveChange?.invoke(false)
        onFileDrop?.invoke(file)
        return true
    }

    private fun updateDragActive(
        event: DragAndDropEvent,
    ) {
        onDragActiveChange?.invoke(isFileDragData(event.dragData()))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class DesktopFileDragMonitor : DragAndDropTarget {
    var onDragActiveChange: ((Boolean) -> Unit)? = null

    fun reset() {
        onDragActiveChange?.invoke(false)
    }

    fun shouldStartDragAndDrop(
        event: DragAndDropEvent,
    ): Boolean = isFileDragData(event.dragData())

    override fun onEntered(event: DragAndDropEvent) {
        updateDragActive(event)
    }

    override fun onMoved(event: DragAndDropEvent) {
        updateDragActive(event)
    }

    override fun onExited(event: DragAndDropEvent) {
        reset()
    }

    override fun onEnded(event: DragAndDropEvent) {
        reset()
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        onDragActiveChange?.invoke(false)
        return false
    }

    private fun updateDragActive(
        event: DragAndDropEvent,
    ) {
        onDragActiveChange?.invoke(isFileDragData(event.dragData()))
    }
}
