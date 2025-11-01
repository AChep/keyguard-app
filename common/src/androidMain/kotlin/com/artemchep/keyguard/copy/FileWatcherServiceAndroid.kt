package com.artemchep.keyguard.copy

import android.os.Build
import android.os.FileObserver
import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.util.contains
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.trySendBlocking
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
        file: File,
    ): Flow<FileWatchEvent> = file.watchFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
fun File.watchFlow(
    tag: Any? = null,
) = callbackFlow<FileWatchEvent> {
    // File Observer is introduced later on, so if you are using an older device,
    // then just disable the feature.
    if (Build.VERSION.SDK_INT < 29) {
        return@callbackFlow
    }

    val observerMask = FileObserver.MODIFY or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.DELETE_SELF
    val observer = object : FileObserver(this@watchFlow, observerMask) {
        override fun onEvent(event: Int, path: String?) {
            val file = path?.let(::File)
                ?: return
            val fileEventKind = if (CREATE in event) {
                FileWatchEvent.Kind.CREATED
            } else if (DELETE in event || DELETE_SELF in event) {
                FileWatchEvent.Kind.DELETED
            } else {
                FileWatchEvent.Kind.MODIFIED
            }
            val fileEvent = FileWatchEvent(
                file = file,
                tag = tag,
                kind = fileEventKind,
            )
            trySendBlocking(fileEvent)
        }
    }

    send(
        FileWatchEvent(
            file = this@watchFlow,
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
