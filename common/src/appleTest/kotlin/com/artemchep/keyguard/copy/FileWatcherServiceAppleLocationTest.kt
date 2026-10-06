package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.appleBookmarkCreationOptions
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import platform.Foundation.NSURL
import platform.posix.rename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bookmark resolution and moves of the observed file. */
@OptIn(ExperimentalForeignApi::class)
class FileWatcherServiceAppleLocationTest {
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

    private fun bookmarkToken(path: String): FileAccessToken {
        val bookmark = assertNotNull(
            NSURL.fileURLWithPath(path).bookmarkDataWithOptions(appleBookmarkCreationOptions, null, null, null),
        )
        return FileAccessToken(bookmark.toSecurityScopedBookmarkToken())
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
}
