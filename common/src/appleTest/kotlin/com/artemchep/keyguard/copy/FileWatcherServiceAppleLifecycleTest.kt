package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.chmod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Foreground changes, eviction and cancellation of an observation. */
@OptIn(ExperimentalForeignApi::class)
class FileWatcherServiceAppleLifecycleTest {
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
}
