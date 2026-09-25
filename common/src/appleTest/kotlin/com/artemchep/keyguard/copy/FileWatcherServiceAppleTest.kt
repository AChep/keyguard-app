package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.appleBookmarkCreationOptions
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.Foundation.NSDate
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFilePresenterProtocol
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFileNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceReferenceDate
import platform.posix.chmod
import platform.posix.free
import platform.posix.realpath
import platform.posix.rename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FileWatcherServiceAppleTest {
    @Test
    fun initializesOnceAndIgnoresUnchangedAndUnrelatedFiles() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path) { events, _ ->
                events.awaitInitialized(path)
                events.assertQuiet()
                write("$root/unrelated.kdbx", "unrelated")
                events.assertQuiet()
            }
        }
    }

    @Test
    fun observesInPlaceWritesThroughBookmark() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path, accessToken = bookmarkToken(path)) { events, _ ->
                events.awaitInitialized(path)
                // Keep the same object and size, so this relies on modification time.
                write(path, "updated")
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun bookmarkFollowsMoveForWatcherAndFileService() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            val movedPath = "$root/moved.kdbx"
            write(path, "moved document contents")
            val originalUri = assertNotNull(NSURL.fileURLWithPath(path).absoluteString)
            val token = bookmarkToken(path)
            withWatcher(path, accessToken = token) { events, _ ->
                events.awaitInitialized(path)
                assertEquals(0, rename(path, movedPath))
                val event = events.awaitMoved(path, movedPath)
                assertTrue(
                    event.kind == FileWatchEvent.Kind.MODIFIED || event.kind == FileWatchEvent.Kind.CREATED,
                )
                assertEquals(
                    "moved document contents",
                    FileServiceApple().readFromFile(originalUri, token).use { it.readString() },
                )
            }
        }
    }

    @Test
    fun staleBookmarkSurvivesReplacementAndForegroundRestart() = runBlocking(Dispatchers.Default) {
        bookmarkSurvivesReplacementAndForegroundRestart(moveBeforeCollection = true)
    }

    @Test
    fun movedBookmarkSurvivesReplacementAndForegroundRestart() = runBlocking(Dispatchers.Default) {
        bookmarkSurvivesReplacementAndForegroundRestart(moveBeforeCollection = false)
    }

    private suspend fun bookmarkSurvivesReplacementAndForegroundRestart(moveBeforeCollection: Boolean) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            val movedPath = "$root/moved.kdbx"
            write(path, "initial")
            val token = bookmarkToken(path)
            if (moveBeforeCollection) assertEquals(0, rename(path, movedPath))
            val foregroundChanged = CompletableDeferred<(Boolean) -> Unit>()
            withWatcher(path, service = lifecycleWatcher(foregroundChanged), accessToken = token) { events, _ ->
                events.awaitInitialized(if (moveBeforeCollection) movedPath else path)
                if (!moveBeforeCollection) {
                    val presenter = presenters(path).single()
                    assertEquals(0, rename(path, movedPath))
                    presenter.presentedItemOperationQueue().addOperationWithBlock {
                        presenter.presentedItemDidMoveToURL(NSURL.fileURLWithPath(movedPath))
                    }
                    events.awaitMoved(path, movedPath)
                }
                events.awaitQuiet(movedPath)
                val replacement = "$root/replacement.tmp"
                write(replacement, "replacement at the new location")
                assertEquals(0, rename(replacement, movedPath))
                events.awaitChange(movedPath, FileWatchEvent.Kind.MODIFIED)
                events.awaitQuiet(movedPath)

                val setForeground = foregroundChanged.await()
                setForeground(false)
                events.assertQuiet()
                setForeground(true)
                events.awaitChange(movedPath, FileWatchEvent.Kind.MODIFIED)
                events.awaitQuiet(movedPath)
                assertEquals(1, presenterCount(movedPath))
                write(movedPath, "changed after foreground restart")
                events.awaitChange(movedPath, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun samplingWaitsForPendingMoveBeforeReadingReusedPath() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            val movedPath = "$root/moved.kdbx"
            write(path, "initial")
            withWatcher(path) { events, _ ->
                events.awaitInitialized(path)
                events.awaitQuiet(path)
                val presenter = presenters(path).single()
                val pendingMove = CompletableDeferred<Unit>()
                val completeMove = CompletableDeferred<Unit>()
                presenter.presentedItemOperationQueue().addOperationWithBlock {
                    pendingMove.complete(Unit)
                    runBlocking { completeMove.await() }
                    presenter.presentedItemDidMoveToURL(NSURL.fileURLWithPath(movedPath))
                }
                try {
                    withTimeout(EVENT_TIMEOUT_MILLIS) { pendingMove.await() }
                    // Let any read prepared before the queued move finish first.
                    events.assertQuiet()
                    assertEquals(0, rename(path, movedPath))
                    write(path, "unrelated file occupying the old path")
                    events.assertQuiet()
                } finally {
                    completeMove.complete(Unit)
                }
                events.awaitChange(movedPath, FileWatchEvent.Kind.MODIFIED)
                events.awaitQuiet(movedPath)
                write(path, "unrelated edit")
                events.assertQuiet()
                write(movedPath, "changed moved file")
                events.awaitChange(movedPath, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun initialAccessFailureEmitsChangeOnRecoveryEvenAcrossBackground() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val directory = "$root/restricted"
            SystemFileSystem.createDirectories(Path(directory))
            val path = "$directory/vault.kdbx"
            write(path, "initial")
            val foregroundChanged = CompletableDeferred<(Boolean) -> Unit>()
            assertEquals(0, chmod(directory, 0u))
            try {
                withWatcher(path, service = lifecycleWatcher(foregroundChanged)) { events, _ ->
                    events.assertQuiet()
                    assertEquals(0, presenterCount(path))
                    val setForeground = foregroundChanged.await()
                    setForeground(false)
                    events.assertQuiet()
                    assertEquals(0, chmod(directory, 448u)) // 0700
                    setForeground(true)
                    events.awaitInitialized(path)
                    events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
                    events.awaitQuiet(path)
                    write(path, "changed after access recovers")
                    events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
                }
            } finally {
                chmod(directory, 448u)
            }
        }
    }

    @Test
    fun detectsAtomicReplacementWithSameSizeAndModificationTime() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "before")
            // Use an exactly representable timestamp for both files: round-tripping
            // a current nanosecond timestamp through NSDate can lose precision.
            assertTrue(
                NSFileManager.defaultManager.setAttributes(
                    mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSinceReferenceDate(1_000.0)),
                    ofItemAtPath = path,
                    error = null,
                ),
            )
            val before = attributes(path)
            withWatcher(path) { events, _ ->
                events.awaitInitialized(path)
                val replacement = "$root/replacement.tmp"
                write(replacement, "after!")
                assertTrue(
                    NSFileManager.defaultManager.setAttributes(
                        mapOf(NSFileModificationDate to assertNotNull(before[NSFileModificationDate])),
                        ofItemAtPath = replacement,
                        error = null,
                    ),
                )
                // A raw rename replaces the filesystem object without notifying a
                // coordinator. Size and timestamp cannot reveal this change.
                assertEquals(0, rename(replacement, path))
                val after = attributes(path)
                assertEquals(before[NSFileSize], after[NSFileSize])
                assertEquals(before[NSFileModificationDate], after[NSFileModificationDate])
                assertNotEquals(before[NSFileSystemFileNumber], after[NSFileSystemFileNumber])
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
                events.awaitQuiet(path)
                write(path, "changed after replacement")
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun observesDeletionAndRecreation() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path) { events, _ ->
                events.awaitInitialized(path)
                assertTrue(NSFileManager.defaultManager.removeItemAtPath(path, null))
                events.awaitChange(path, FileWatchEvent.Kind.DELETED)
                write(path, "recreated")
                events.awaitChange(path, FileWatchEvent.Kind.CREATED)
                events.awaitQuiet(path)
                write(path, "changed after recreation")
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun observesFileCreatedAfterCollectionStarts() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/missing.kdbx"
            withWatcher(path) { events, _ ->
                events.awaitInitialized(path)
                events.assertQuiet()
                write(path, "created")
                events.awaitChange(path, FileWatchEvent.Kind.CREATED)
            }
        }
    }

    @Test
    fun cancellationRemovesRegisteredPresenter() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path) { events, job ->
                events.awaitInitialized(path)
                assertEquals(1, presenterCount(path))
                withTimeout(EVENT_TIMEOUT_MILLIS) { job.cancelAndJoin() }
                assertEquals(0, presenterCount(path))
            }
        }
    }

    @Test
    fun cancellationDoesNotWaitForQueuedReadPreparation() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path) { events, job ->
                events.awaitInitialized(path)
                events.awaitQuiet(path)
                val presenter = presenters(path).single()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val queue = presenter.presentedItemOperationQueue()
                queue.addOperationWithBlock {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                }
                try {
                    withTimeout(EVENT_TIMEOUT_MILLIS) { entered.await() }
                    events.assertQuiet()
                    withTimeout(EVENT_TIMEOUT_MILLIS) { job.cancelAndJoin() }
                    assertEquals(0, presenterCount(path))
                } finally {
                    release.complete(Unit)
                }
                queue.waitUntilAllOperationsAreFinished()
                assertEquals(0, presenterCount(path))
            }
        }
    }

    @Test
    fun backgroundRemovesPresenterSynchronouslyAndResumeInvalidates() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            val foregroundChanged = CompletableDeferred<(Boolean) -> Unit>()
            withWatcher(path, service = lifecycleWatcher(foregroundChanged)) { events, _ ->
                events.awaitInitialized(path)
                val setForeground = withTimeout(EVENT_TIMEOUT_MILLIS) { foregroundChanged.await() }
                assertEquals(1, presenterCount(path))
                setForeground(false)
                // This check deliberately does not suspend: iOS may suspend the
                // process as soon as its background notification returns.
                assertEquals(0, presenterCount(path))
                events.assertQuiet()
                setForeground(true)
                // An unchanged file must still invalidate after an unobserved interval.
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
                assertEquals(1, presenterCount(path))
            }
        }
    }

    @Test
    fun backgroundDoesNotObserveWritesUntilResume() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            val foregroundChanged = CompletableDeferred<(Boolean) -> Unit>()
            withWatcher(path, service = lifecycleWatcher(foregroundChanged)) { events, _ ->
                events.awaitInitialized(path)
                val setForeground = withTimeout(EVENT_TIMEOUT_MILLIS) { foregroundChanged.await() }
                setForeground(false)
                write(path, "changed while in background")
                events.assertQuiet()
                assertEquals(0, presenterCount(path))
                setForeground(true)
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun presenterChangeInvalidatesUnchangedMetadata() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path) { events, _ ->
                events.awaitInitialized(path)
                events.assertQuiet()
                presenters(path).single().presentedItemDidChange()
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
            }
        }
    }

    @Test
    fun evictionRemovesPresenterWithoutInvalidatingUntilForegroundRestart() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            val foregroundChanged = CompletableDeferred<(Boolean) -> Unit>()
            withWatcher(path, service = lifecycleWatcher(foregroundChanged)) { events, _ ->
                events.awaitInitialized(path)
                val setForeground = withTimeout(EVENT_TIMEOUT_MILLIS) { foregroundChanged.await() }
                var completed = false
                var succeeded = false
                var removedBeforeCompletion = false
                presenters(path).single().accommodatePresentedItemEvictionWithCompletionHandler { error ->
                    succeeded = error == null
                    removedBeforeCompletion = presenterCount(path) == 0
                    completed = true
                }
                assertTrue(completed)
                assertTrue(succeeded)
                assertTrue(removedBeforeCompletion)
                assertEquals(0, presenterCount(path))
                events.assertQuiet()
                assertEquals(0, presenterCount(path))
                // Reporting the same foreground state is not a new observation session.
                setForeground(true)
                events.assertQuiet()
                assertEquals(0, presenterCount(path))
                setForeground(false)
                setForeground(true)
                events.awaitChange(path, FileWatchEvent.Kind.MODIFIED)
                assertEquals(1, presenterCount(path))
            }
        }
    }

    @Test
    fun invalidBookmarkDoesNotFallBackToRawUri() = runBlocking(Dispatchers.Default) {
        withFolder { root ->
            val path = "$root/vault.kdbx"
            write(path, "initial")
            withWatcher(path, accessToken = FileAccessToken("invalid bookmark")) { events, _ ->
                events.assertQuiet()
                assertEquals(0, presenterCount(path))
                write(path, "changed")
                events.assertQuiet()
                assertEquals(0, presenterCount(path))
            }
        }
    }

    private suspend fun withWatcher(
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
    private fun lifecycleWatcher(
        foregroundChanged: CompletableDeferred<(Boolean) -> Unit> = CompletableDeferred(),
    ) = FileWatcherServiceApple(POLL_INTERVAL_MILLIS) { callback ->
        callback(true)
        foregroundChanged.complete(callback)
        awaitCancellation()
    }

    private fun bookmarkToken(path: String): FileAccessToken {
        val bookmark = assertNotNull(
            NSURL.fileURLWithPath(path).bookmarkDataWithOptions(appleBookmarkCreationOptions, null, null, null),
        )
        return FileAccessToken(bookmark.toSecurityScopedBookmarkToken())
    }

    private suspend fun Channel<FileWatchEvent>.awaitInitialized(path: String) {
        val event = withTimeout(EVENT_TIMEOUT_MILLIS) { receive() }
        assertEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
        assertEquals(canonicalPath(path), canonicalPath(event.path.value))
    }

    private suspend fun Channel<FileWatchEvent>.awaitChange(path: String, kind: FileWatchEvent.Kind) {
        withTimeout(EVENT_TIMEOUT_MILLIS) {
            do {
                val event = receive()
                assertNotEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
                assertEquals(canonicalPath(path), canonicalPath(event.path.value))
            } while (event.kind != kind)
        }
    }

    /** Returns the first event reported at [movedPath]. */
    private suspend fun Channel<FileWatchEvent>.awaitMoved(
        path: String,
        movedPath: String,
    ): FileWatchEvent = withTimeout(EVENT_TIMEOUT_MILLIS) {
        var event: FileWatchEvent
        do {
            event = receive()
            assertNotEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
            assertTrue(canonicalPath(event.path.value) in setOf(canonicalPath(path), canonicalPath(movedPath)))
        } while (canonicalPath(event.path.value) != canonicalPath(movedPath))
        event
    }

    private suspend fun Channel<FileWatchEvent>.assertQuiet() {
        assertNull(withTimeoutOrNull(QUIET_INTERVAL_MILLIS) { receive() })
    }

    private suspend fun Channel<FileWatchEvent>.awaitQuiet(path: String) {
        withTimeout(EVENT_TIMEOUT_MILLIS) {
            while (true) {
                val event = withTimeoutOrNull(QUIET_INTERVAL_MILLIS) { receive() }
                    ?: return@withTimeout
                assertNotEquals(FileWatchEvent.Kind.INITIALIZED, event.kind)
                assertEquals(canonicalPath(path), canonicalPath(event.path.value))
            }
        }
    }

    private fun presenterCount(path: String): Int = presenters(path).size

    private fun presenters(path: String): List<NSFilePresenterProtocol> = NSFileCoordinator.filePresenters
        .filterIsInstance<NSFilePresenterProtocol>()
        .filter { it.presentedItemURL()?.path?.let(::canonicalPath) == canonicalPath(path) }

    // NSURL may shorten /private/var to /var even when asked to resolve symlinks,
    // whereas bookmark resolution returns /private/var. Canonicalize the parent
    // through POSIX, including for deleted test files.
    private fun canonicalPath(path: String): String {
        val parent = assertNotNull(realpath(path.substringBeforeLast('/'), null))
        return try {
            parent.toKString() + "/" + path.substringAfterLast('/')
        } finally {
            free(parent)
        }
    }

    private fun attributes(path: String): Map<Any?, *> = assertNotNull(
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null),
    )

    private fun write(path: String, content: String) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(content) }
    }

    private suspend fun withFolder(block: suspend (String) -> Unit) {
        val root = NSTemporaryDirectory() + "keyguard-file-watcher-test-" + NSUUID().UUIDString
        SystemFileSystem.createDirectories(Path(root))
        try {
            block(root)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 25L
        const val QUIET_INTERVAL_MILLIS = 200L
        const val EVENT_TIMEOUT_MILLIS = 5_000L
    }
}
