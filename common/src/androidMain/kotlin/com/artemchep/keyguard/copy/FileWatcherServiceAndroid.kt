package com.artemchep.keyguard.copy

import android.os.Build
import android.os.FileObserver
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.util.contains
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.toJavaFile
import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.kodein.di.DirectDI
import java.io.File
import java.lang.Exception

class FileWatcherServiceAndroid(
) : FileWatcherService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun fileChangedFlow(
        file: LocalPath,
    ): Flow<FileWatchEvent> = file.toJavaFile().watchFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
fun File.watchFlow(
    tag: Any? = null,
) = callbackFlow<FileWatchEvent> {
    val observerMask = FileObserver.MODIFY or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.DELETE_SELF

    fun handleEvent(event: Int, path: String?) {
        val file = path?.let(::File)
            ?: return
        val fileEventKind = if (FileObserver.CREATE in event) {
            FileWatchEvent.Kind.CREATED
        } else if (FileObserver.DELETE in event || FileObserver.DELETE_SELF in event) {
            FileWatchEvent.Kind.DELETED
        } else {
            FileWatchEvent.Kind.MODIFIED
        }
        val fileEvent = FileWatchEvent(
            path = this@watchFlow.resolve(file.path).toLocalPath(),
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
            path = this@watchFlow.toLocalPath(),
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
