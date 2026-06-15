package com.artemchep.keyguard.copy

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.util.contains
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.toJavaFile
import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File
import java.lang.Exception
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class FileWatcherServiceAndroid(
    private val context: Context,
) : FileWatcherService {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent> {
        // We can only watch directories, not files.
        val javaFile = file.toJavaFile().absoluteFile
        if (!javaFile.isDirectory) {
            val parentFile = javaFile.parentFile
            if (parentFile != null) {
                return parentFile.watchFlow(
                    filter = javaFile,
                )
            }
        }

        return javaFile.watchFlow()
    }

    override fun uriChangedFlow(
        uri: String,
    ): Flow<FileWatchEvent> {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            ContentResolver.SCHEME_FILE -> parsedUri
                .toFile()
                .toLocalPath()
                .let(::fileChangedFlow)

            ContentResolver.SCHEME_CONTENT -> context
                .contentResolver
                .watchContentUriFlow(parsedUri)

            else -> super.uriChangedFlow(uri)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun File.watchFlow(
    tag: Any? = null,
    filter: File? = null,
) = callbackFlow<FileWatchEvent> {
    val filterFile = filter?.absoluteFile
    val filterIsDirectory = filterFile?.isDirectory == true
    val observerMask = FileObserver.MODIFY or
            FileObserver.CLOSE_WRITE or
            FileObserver.ATTRIB or
            FileObserver.CREATE or
            FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or
            FileObserver.MOVE_SELF or
            FileObserver.DELETE or
            FileObserver.DELETE_SELF

    fun handleEvent(event: Int, path: String?) {
        val file = path
            ?.let { this@watchFlow.resolve(it) }
            ?: this@watchFlow
        val isAccepted = file.isAcceptedFileWatchEvent(
            filter = filterFile,
            filterIsDirectory = filterIsDirectory,
        )
        if (!isAccepted) {
            return
        }

        val fileEventKind = if (FileObserver.CREATE in event) {
            FileWatchEvent.Kind.CREATED
        } else if (FileObserver.MOVED_TO in event) {
            FileWatchEvent.Kind.CREATED
        } else if (
            FileObserver.DELETE in event ||
            FileObserver.DELETE_SELF in event ||
            FileObserver.MOVED_FROM in event ||
            FileObserver.MOVE_SELF in event
        ) {
            FileWatchEvent.Kind.DELETED
        } else {
            FileWatchEvent.Kind.MODIFIED
        }
        val fileEvent = FileWatchEvent(
            path = file.toLocalPath(),
            tag = tag,
            kind = fileEventKind,
        )
        trySend(fileEvent)
    }

    fun handleEventSafely(event: Int, path: String?) {
        try {
            handleEvent(event, path)
        } catch (e: Exception) {
            recordException(e)
        }
    }

    val observer = fileObserver(
        file = this@watchFlow,
        mask = observerMask,
        onEvent = ::handleEventSafely,
    )

    send(
        FileWatchEvent(
            path = (filter ?: this@watchFlow)
                .toLocalPath(),
            tag = tag,
            kind = FileWatchEvent.Kind.INITIALIZED,
        ),
    )

    try {
        observer.startWatching()
        awaitCancellation()
    } finally {
        try {
            observer.stopWatching()
        } catch (_: Exception) {
        }
    }
}.flowOn(Dispatchers.IO)

internal fun File.isAcceptedFileWatchEvent(
    filter: File?,
    filterIsDirectory: Boolean = filter?.isDirectory == true,
): Boolean {
    if (filter == null) {
        return true
    }

    val eventPath = toNormalizedAbsolutePath()
    val filterPath = filter.toNormalizedAbsolutePath()
    if (eventPath == filterPath) {
        return true
    }

    return filterIsDirectory && eventPath.startsWith(filterPath)
}

private fun File.toNormalizedAbsolutePath(): Path = absoluteFile
    .toPath()
    .normalize()

private fun fileObserver(
    file: File,
    mask: Int,
    onEvent: (Int, String?) -> Unit,
): FileObserver =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(file, mask) {
            override fun onEvent(event: Int, path: String?) {
                onEvent(event, path)
            }
        }
    } else {
        legacyFileObserver(
            file = file,
            mask = mask,
            onEvent = onEvent,
        )
    }

@Suppress("DEPRECATION")
private fun legacyFileObserver(
    file: File,
    mask: Int,
    onEvent: (Int, String?) -> Unit,
): FileObserver = object : FileObserver(file.path, mask) {
    override fun onEvent(event: Int, path: String?) {
        onEvent(event, path)
    }
}

private const val CONTENT_URI_POLL_INTERVAL_MS = 15_000L

@OptIn(ExperimentalCoroutinesApi::class)
private fun ContentResolver.watchContentUriFlow(
    uri: Uri,
    tag: Any? = null,
) = callbackFlow<FileWatchEvent> {
    fun trySendEvent(
        kind: FileWatchEvent.Kind,
        changedUri: Uri? = null,
    ) {
        val event = FileWatchEvent(
            path = LocalPath((changedUri ?: uri).toString()),
            tag = tag,
            kind = kind,
        )
        trySend(event)
    }

    trySendEvent(FileWatchEvent.Kind.INITIALIZED)

    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySendEvent(FileWatchEvent.Kind.MODIFIED)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            trySendEvent(
                kind = FileWatchEvent.Kind.MODIFIED,
                changedUri = uri,
            )
        }

        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            trySendEvent(
                kind = flags.toFileWatchEventKind(),
                changedUri = uri,
            )
        }
    }

    val observerRegistered = runCatching {
        registerContentObserver(uri, true, observer)
        true
    }.getOrElse { e ->
        recordException(e)
        false
    }

    val pollingJob = launch(Dispatchers.IO) {
        var lastFingerprint = readContentUriFingerprint(uri)
        while (isActive) {
            delay(CONTENT_URI_POLL_INTERVAL_MS.milliseconds)

            val currentFingerprint = readContentUriFingerprint(uri)
            if (currentFingerprint == lastFingerprint) {
                continue
            }

            val kind = if (currentFingerprint == null) {
                FileWatchEvent.Kind.DELETED
            } else {
                FileWatchEvent.Kind.MODIFIED
            }
            lastFingerprint = currentFingerprint
            trySendEvent(kind)
        }
    }

    awaitClose {
        pollingJob.cancel()

        if (observerRegistered) {
            try {
                unregisterContentObserver(observer)
            } catch (e: Exception) {
                recordException(e)
            }
        }
    }
}.flowOn(Dispatchers.IO)

private data class ContentUriFingerprint(
    val size: Long?,
    val lastModified: Long?,
)

private fun ContentResolver.readContentUriFingerprint(
    uri: Uri,
): ContentUriFingerprint? = runCatching {
    val projection = arrayOf(
        OpenableColumns.SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    query(uri, projection, null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }

            ContentUriFingerprint(
                size = cursor.getLongOrNull(OpenableColumns.SIZE),
                lastModified = cursor.getLongOrNull(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
            )
        }
}.getOrNull()

private fun android.database.Cursor.getLongOrNull(
    columnName: String,
): Long? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return null
    }

    return getLong(index)
}

private fun Int.toFileWatchEventKind(): FileWatchEvent.Kind {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (ContentResolver.NOTIFY_INSERT in this) {
            return FileWatchEvent.Kind.CREATED
        }
        if (ContentResolver.NOTIFY_DELETE in this) {
            return FileWatchEvent.Kind.DELETED
        }
    }

    return FileWatchEvent.Kind.MODIFIED
}
