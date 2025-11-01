package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.kodein.di.DirectDI
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class FileWatcherServiceJvm(
) : FileWatcherService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun fileChangedFlow(
        file: File,
    ): Flow<FileWatchEvent> = flow {
        val isDir = file.isDirectory
        if (isDir) {
            file.watchDirectoryFlow()
                .collect(this)
            return@flow
        }

        // If we are pointing to a file, that is a single file in a directory
        // then we can do use a proper watcher, otherwise we have to resort to a
        // dump checker.

        val single = kotlin.run {
            val parent = file.parentFile
            parent?.list()?.size == 1
        }
        if (single) {
            file.watchDirectoryFlow()
                .collect(this)
            return@flow
        }

        file.watchFileFlow()
            .collect(this)
    }.flowOn(Dispatchers.IO)
}

/**
 * Watches a single file for the change, doesn't use pooling, instead it just
 * periodically checks whether the file has changed or not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun File.watchFileFlow(
    tag: Any? = null,
) = callbackFlow<FileWatchEvent> {
    send(
        FileWatchEvent(
            file = this@watchFileFlow,
            tag = tag,
            kind = FileWatchEvent.Kind.INITIALIZED,
        ),
    )

    fun getLastModifiedTime(): Long {
        try {
            val path = toPath()
            return Files.getLastModifiedTime(path)
                .toMillis()
        } catch (_: IOException) {
            // Ignore
        }
        return 0L
    }

    var lastModifiedTime = getLastModifiedTime()
    try {
        while (isActive) {
            val curModifiedTime = getLastModifiedTime()
            if (curModifiedTime != lastModifiedTime) {
                val fileEvent = FileWatchEvent(
                    file = this@watchFileFlow,
                    tag = tag,
                    kind = FileWatchEvent.Kind.MODIFIED,
                )
                trySendBlocking(fileEvent)

                // Remember the new time last modified.
                lastModifiedTime = curModifiedTime
            }

            delay(1000L)
            ensureActive()
        }
    } finally {
        // Do nothing
    }
}.flowOn(Dispatchers.IO)

@OptIn(ExperimentalCoroutinesApi::class)
fun File.watchDirectoryFlow(
    tag: Any? = null,
) = callbackFlow<FileWatchEvent> {
    val path: Path = run {
        val file = this@watchDirectoryFlow
        if (file.isFile) {
            file.parentFile
        } else {
            file
        }.toPath()
    }

    fun sendEvent(
        path: Path,
        kind: FileWatchEvent.Kind,
    ) = run {
        trySendBlocking(
            FileWatchEvent(
                file = path.toFile(),
                tag = tag,
                kind = kind,
            ),
        )
    }

    sendEvent(
        path = path,
        kind = FileWatchEvent.Kind.INITIALIZED,
    )

    var watcher: DirectoryWatcher? = null
    try {
        watcher = DirectoryWatcher.builder()
            .path(path)
            .fileHashing(false)
            .listener { event: DirectoryChangeEvent ->
                if (this@watchDirectoryFlow.isFile) {
                    // Check that the event is related only to this
                    // file!
                    if (this@watchDirectoryFlow.toPath() != event.path()) {
                        return@listener
                    }
                }
                val kind = when (event.eventType()) {
                    DirectoryChangeEvent.EventType.CREATE -> FileWatchEvent.Kind.CREATED
                    DirectoryChangeEvent.EventType.OVERFLOW,
                    DirectoryChangeEvent.EventType.MODIFY,
                        -> FileWatchEvent.Kind.MODIFIED

                    DirectoryChangeEvent.EventType.DELETE -> FileWatchEvent.Kind.DELETED
                    else -> return@listener
                }
                sendEvent(
                    path = event.path(),
                    kind = kind,
                )
            }
            .build()
        watcher?.watch()
    } finally {
        try {
            watcher?.close()
        } catch (_: Exception) {
        }
    }
}.flowOn(Dispatchers.IO)
