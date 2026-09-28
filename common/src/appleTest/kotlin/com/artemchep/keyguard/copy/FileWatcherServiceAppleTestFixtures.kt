@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.file.FileAccessToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePresenterProtocol
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.free
import platform.posix.realpath
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal suspend fun withWatcher(
    path: String,
    service: FileWatcherServiceApple = lifecycleWatcher(),
    accessToken: FileAccessToken? = null,
    block: suspend (Channel<FileWatchEvent>, Job) -> Unit,
) = coroutineScope {
    val events = Channel<FileWatchEvent>(Channel.UNLIMITED)
    val job = launch {
        service.uriChangedFlow(assertNotNull(NSURL.fileURLWithPath(path).absoluteString), accessToken)
            .collect { events.send(it) }
    }
    try {
        block(events, job)
    } finally {
        withContext(NonCancellable) {
            job.cancelAndJoin()
            events.close()
        }
    }
}

/** Starts in the foreground and hands the lifecycle callback to [foregroundChanged]. */
internal fun lifecycleWatcher(
    foregroundChanged: CompletableDeferred<(Boolean) -> Unit> = CompletableDeferred(),
) = FileWatcherServiceApple(POLL_INTERVAL_MILLIS) { callback ->
    callback(true)
    foregroundChanged.complete(callback)
    awaitCancellation()
}

internal suspend fun Channel<FileWatchEvent>.awaitInitialized(path: String) {
    val event = withTimeout(EVENT_TIMEOUT_MILLIS) { receive() }
    assertEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
    assertEquals(canonicalPath(path), canonicalPath(event.path.value))
}

internal suspend fun Channel<FileWatchEvent>.awaitChange(path: String, kind: FileWatchEvent.Kind) {
    withTimeout(EVENT_TIMEOUT_MILLIS) {
        do {
            val event = receive()
            assertNotEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
            assertEquals(canonicalPath(path), canonicalPath(event.path.value))
        } while (event.kind != kind)
    }
}

internal suspend fun Channel<FileWatchEvent>.assertQuiet() {
    assertNull(withTimeoutOrNull(QUIET_INTERVAL_MILLIS) { receive() })
}

internal suspend fun Channel<FileWatchEvent>.awaitQuiet(path: String) {
    withTimeout(EVENT_TIMEOUT_MILLIS) {
        while (true) {
            val event = withTimeoutOrNull(QUIET_INTERVAL_MILLIS) { receive() }
                ?: return@withTimeout
            assertNotEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
            assertEquals(canonicalPath(path), canonicalPath(event.path.value))
        }
    }
}

internal fun presenterCount(path: String): Int = presenters(path).size

internal fun presenters(path: String): List<NSFilePresenterProtocol> = NSFileCoordinator.filePresenters
    .filterIsInstance<NSFilePresenterProtocol>()
    .filter { it.presentedItemURL()?.path?.let(::canonicalPath) == canonicalPath(path) }

// NSURL may shorten /private/var to /var even when asked to resolve symlinks,
// whereas bookmark resolution returns /private/var. Canonicalize the parent
// through POSIX, including for deleted test files.
internal fun canonicalPath(path: String): String {
    val parent = assertNotNull(realpath(path.substringBeforeLast('/'), null))
    return try {
        parent.toKString() + "/" + path.substringAfterLast('/')
    } finally {
        free(parent)
    }
}

internal fun write(path: String, content: String) {
    SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(content) }
}

internal suspend fun withFolder(block: suspend (String) -> Unit) {
    val root = NSTemporaryDirectory() + "keyguard-file-watcher-test-" + NSUUID().UUIDString
    SystemFileSystem.createDirectories(Path(root))
    try {
        block(root)
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }
}

private const val POLL_INTERVAL_MILLIS = 25L

private const val QUIET_INTERVAL_MILLIS = 200L

internal const val EVENT_TIMEOUT_MILLIS = 5_000L
