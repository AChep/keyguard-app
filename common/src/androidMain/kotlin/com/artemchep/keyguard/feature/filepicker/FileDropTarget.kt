package com.artemchep.keyguard.feature.filepicker

import android.app.Activity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragActiveChange: (Boolean) -> Unit,
    onFileDrop: (FilePickerResult) -> Unit,
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
        target.onDragActiveChange = onDragActiveChange
        target.onFileDrop = onFileDrop
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
    var onDragActiveChange: ((Boolean) -> Unit)? = null
    var onFileDrop: ((FilePickerResult) -> Unit)? = null

    private var permissions: DragAndDropPermissions? = null
    private var dropJob: Job? = null

    fun reset() {
        onDragActiveChange?.invoke(false)
        dropJob?.cancel()
        dropJob = null
        permissions?.release()
        permissions = null
    }

    fun shouldStartDragAndDrop(
        event: DragAndDropEvent,
    ): Boolean {
        val uri = event.firstUriOrNull()
            ?: return false
        permissions?.release()
        permissions = null

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val activity = activity
                ?: return false
            permissions = requestPermissions(
                activity = activity,
                event = event,
            ) ?: return false
        }

        return true
    }

    override fun onEntered(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(true)
    }

    override fun onExited(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(false)
    }

    override fun onEnded(event: DragAndDropEvent) {
        onDragActiveChange?.invoke(false)
        if (dropJob?.isActive != true) {
            permissions?.release()
            permissions = null
        }
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val context = context
            ?: return false
        val scope = scope
            ?: return false
        val uri = event.firstUriOrNull()
            ?: return false
        val currentPermissions = permissions
        permissions = null

        onDragActiveChange?.invoke(false)
        dropJob?.cancel()
        val job = scope.launch {
            try {
                val file = runCatching {
                    context.toDroppedFilePickerResult(uri)
                }.getOrNull()
                    ?: return@launch
                onFileDrop?.invoke(file)
            } finally {
                currentPermissions?.release()
            }
        }
        dropJob = job
        job.invokeOnCompletion {
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
    ): Boolean = event.firstUriOrNull() != null

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

private fun DragAndDropEvent.firstUriOrNull(): Uri? {
    val clipData = toAndroidDragEvent().clipData
        ?: return null
    for (index in 0 until clipData.itemCount) {
        val uri = clipData.getItemAt(index).uri
        if (uri != null) {
            return uri
        }
    }
    return null
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
