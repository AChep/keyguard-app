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
internal actual fun Modifier.platformFileDropTarget(
    enabled: Boolean,
    multiple: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onTooManyFiles: () -> Unit,
    onFilesDrop: (List<FilePickerResult>) -> Unit,
): Modifier {
    val target = remember {
        DesktopFileDropTarget()
    }

    SideEffect {
        target.multiple = multiple
        target.onDragActiveChange = onDragActiveChange
        target.onTooManyFiles = onTooManyFiles
        target.onFilesDrop = onFilesDrop
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
internal fun droppedFileResults(
    dragData: DragData,
    limit: Int = Int.MAX_VALUE,
): List<FilePickerResult> = when (dragData) {
    is DragData.FilesList -> runCatching {
        dragData.readFiles()
    }.getOrNull()
        ?.let { droppedFileResults(it, limit) }
        .orEmpty()

    else -> emptyList()
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun isFileDragData(
    dragData: DragData,
): Boolean = dragData is DragData.FilesList

/**
 * Each file is checked on disk, so pass a [limit] to stop
 * early if only that many files matter.
 */
internal fun droppedFileResults(
    fileUris: List<String>,
    limit: Int = Int.MAX_VALUE,
): List<FilePickerResult> = fileUris
    .asSequence()
    .mapNotNull(::droppedFileResult)
    .take(limit)
    .toList()

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
    var multiple: Boolean = false
    var onDragActiveChange: ((Boolean) -> Unit)? = null
    var onTooManyFiles: (() -> Unit)? = null
    var onFilesDrop: ((List<FilePickerResult>) -> Unit)? = null

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
        // Two files are enough to reject a single file drop.
        val limit = if (multiple) Int.MAX_VALUE else 2
        val files = droppedFileResults(event.dragData(), limit)
        if (files.isEmpty()) {
            return false
        }

        onDragActiveChange?.invoke(false)
        val accepted = multiple || files.size == 1
        if (accepted) {
            onFilesDrop?.invoke(files)
        } else {
            onTooManyFiles?.invoke()
        }
        return accepted
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
