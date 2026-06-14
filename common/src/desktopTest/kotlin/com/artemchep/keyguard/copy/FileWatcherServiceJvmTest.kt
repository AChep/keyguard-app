package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class FileWatcherServiceJvmTest {
    @Test
    fun `directory watcher cancellation closes blocking watcher`() = runBlocking {
        val root = createTempDirectory("file-watcher")
        try {
            val changed = CompletableDeferred<FileWatchEvent>()
            val job = launch {
                root.toFile()
                    .watchDirectoryFlow()
                    .onEach { event ->
                        if (event.kind != FileWatchEvent.Kind.INITIALIZED) {
                            changed.complete(event)
                        }
                    }
                    .collect()
            }

            val event = withTimeout(5_000L) {
                var attempt = 0
                while (!changed.isCompleted) {
                    root.resolve("watched-$attempt.txt")
                        .writeText("$attempt")
                    delay(50L)
                    attempt++
                }
                changed.await()
            }
            assertTrue(
                event.kind == FileWatchEvent.Kind.CREATED ||
                    event.kind == FileWatchEvent.Kind.MODIFIED,
            )

            delay(100L)

            withTimeout(5_000L) {
                job.cancelAndJoin()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
