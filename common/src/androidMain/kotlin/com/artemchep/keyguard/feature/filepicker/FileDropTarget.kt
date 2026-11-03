package com.artemchep.keyguard.feature.filepicker

import android.app.Activity
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.DragAndDropPermissions
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.platform.LeUriImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun Modifier.platformFileDropTarget(
    enabled: Boolean,
    multiple: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onTooManyFiles: () -> Unit,
    onFilesDrop: (List<FilePickerResult>) -> Unit,
): Modifier {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = remember {
        AndroidFileDropTarget()
    }
    LaunchedEffect(context) {
        AndroidFileDropStorage.cleanUpStale(context.applicationContext)
    }

    SideEffect {
        target.activity = context.closestActivityOrNull
        target.context = context.applicationContext
        target.scope = scope
        target.multiple = multiple
        target.onDragActiveChange = onDragActiveChange
        target.onTooManyFiles = onTooManyFiles
        target.onFilesDrop = onFilesDrop
        if (!enabled) {
            target.reset()
        }
    }
    DisposableEffect(target) {
        onDispose {
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

@Composable
internal actual fun Modifier.fileDragMonitor(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier {
    val target = remember {
        AndroidFileDragMonitor()
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

private class AndroidFileDropTarget : DragAndDropTarget {
    var activity: Activity? = null
    var context: Context? = null
    var scope: CoroutineScope? = null
    var multiple: Boolean = false
    var onDragActiveChange: ((Boolean) -> Unit)? = null
    var onTooManyFiles: (() -> Unit)? = null
    var onFilesDrop: ((List<FilePickerResult>) -> Unit)? = null

    private var dropJob: Job? = null

    fun reset() {
        onDragActiveChange?.invoke(false)
        dropJob?.cancel()
        dropJob = null
    }

    fun shouldStartDragAndDrop(
        event: DragAndDropEvent,
    ): Boolean = event.mayContainFiles()

    override fun onEntered(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(true)
    }

    override fun onExited(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(false)
    }

    override fun onEnded(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(false)
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val context = context
            ?: return false
        val scope = scope
            ?: return false
        val uris = event.uris()
        if (uris.isEmpty()) {
            return false
        }

        onDragActiveChange?.invoke(false)
        if (!multiple && uris.size > 1) {
            onTooManyFiles?.invoke()
            return false
        }

        // The permissions are only attached to
        // the drop event.
        val permissions = if (uris.any { it.scheme == ContentResolver.SCHEME_CONTENT }) {
            val activity = activity
                ?: return false
            requestPermissions(
                activity = activity,
                event = event,
            ) ?: return false
        } else {
            null
        }

        dropJob?.cancel()
        val job = scope.launch {
            val files = uris.mapNotNull { uri ->
                runCatching {
                    context.toDroppedFilePickerResult(uri)
                }.getOrNull()
            }
            // The reads above swallow the cancellation.
            ensureActive()
            if (files.isEmpty()) {
                return@launch
            }
            onFilesDrop?.invoke(files)
        }
        dropJob = job
        // Also runs if the job is cancelled before it starts.
        job.invokeOnCompletion {
            permissions?.release()
            if (dropJob === job) {
                dropJob = null
            }
        }
        return true
    }
}

private class AndroidFileDragMonitor : DragAndDropTarget {
    var onDragActiveChange: ((Boolean) -> Unit)? = null

    fun reset() {
        onDragActiveChange?.invoke(false)
    }

    fun shouldStartDragAndDrop(
        event: DragAndDropEvent,
    ): Boolean = event.mayContainFiles()

    override fun onEntered(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(true)
    }

    override fun onExited(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(false)
    }

    override fun onEnded(event: DragAndDropEvent) {
        reset()
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        onDragActiveChange?.invoke(false)
        return false
    }
}

/**
 * The clip data is only available on drop, so at the start of
 * a drag we can only guess by its description. A URI item
 * advertises the MIME type of its file, so text types must stay
 * eligible; the URIs are validated on drop.
 */
private fun DragAndDropEvent.mayContainFiles(): Boolean {
    val description = toAndroidDragEvent().clipDescription
        ?: return false
    return (0 until description.mimeTypeCount)
        .any { index ->
            description.getMimeType(index) != ClipDescription.MIMETYPE_TEXT_INTENT
        }
}

private fun DragAndDropEvent.uris(): List<Uri> {
    val clipData = toAndroidDragEvent().clipData
        ?: return emptyList()
    return (0 until clipData.itemCount)
        .mapNotNull { index ->
            clipData.getItemAt(index).uri
        }
}

private fun requestPermissions(
    activity: Activity,
    event: DragAndDropEvent,
): DragAndDropPermissions? = activity.requestDragAndDropPermissions(event.toAndroidDragEvent())

private suspend fun Context.toDroppedFilePickerResult(
    uri: Uri,
): FilePickerResult = withContext(Dispatchers.IO) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
        return@withContext toFilePickerResult(uri)
    }

    if (takePersistableReadPermission(uri)) {
        return@withContext toFilePickerResult(uri)
    }

    val source = toFilePickerResult(uri)
    val copiedFile = AndroidFileDropStorage.copy(
        context = this@toDroppedFilePickerResult,
        sourceUri = uri,
        displayName = source.name,
    )
    val copiedUri = Uri.fromFile(copiedFile)
    FilePickerResult(
        uri = LeUriImpl(copiedUri),
        name = source.name,
        size = source.size ?: copiedFile.length(),
    )
}

private fun Context.takePersistableReadPermission(
    uri: Uri,
): Boolean = runCatching {
    contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
    true
}.getOrDefault(false)
