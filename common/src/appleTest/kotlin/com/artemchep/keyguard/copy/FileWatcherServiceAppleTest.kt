package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFileNumber
import platform.Foundation.dateWithTimeIntervalSinceReferenceDate
import platform.posix.rename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Change detection for a file that stays at its location. */
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

    private fun attributes(path: String): Map<Any?, *> = assertNotNull(
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null),
    )
}
