package com.artemchep.keyguard.desktop.util

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean

internal class SingleInstanceManager(
    private val lockIdentifier: String,
    private val lockFilesDir: Path = Path.of(System.getProperty("java.io.tmpdir")),
    private val watchServiceFactory: () -> WatchService = FileSystems.getDefault()::newWatchService,
) : Closeable {
    private val closed = AtomicBoolean()
    private val lockFilePath = lockFilesDir.resolve("$lockIdentifier.lock")
    private val restoreRequestFilePath = lockFilesDir.resolve("$lockIdentifier.restore_request")

    private var fileChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var watchService: WatchService? = null

    @Synchronized
    fun isSingleInstance(onRestoreRequest: () -> Unit): Boolean {
        check(!closed.get()) { "SingleInstanceManager is closed." }
        return if (fileLock?.isValid == true) {
            true
        } else {
            acquireInstanceLock(onRestoreRequest)
        }
    }

    private fun acquireInstanceLock(onRestoreRequest: () -> Unit): Boolean {
        Files.createDirectories(lockFilesDir)
        val channel = FileChannel.open(
            lockFilePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        return if (lock == null) {
            channel.close()
            sendRestoreRequest()
            false
        } else {
            fileChannel = channel
            fileLock = lock
            var initialized = false
            try {
                startRestoreRequestWatcher(onRestoreRequest)
                Runtime.getRuntime().addShutdownHook(
                    Thread(::close, "keyguard-single-instance-shutdown"),
                )
                initialized = true
            } finally {
                if (!initialized) {
                    close()
                }
            }
            true
        }
    }

    private fun startRestoreRequestWatcher(onRestoreRequest: () -> Unit) {
        val watcher = watchServiceFactory()
        watchService = watcher
        lockFilesDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)

        // A second instance may create the marker after the lock is acquired but
        // before the watcher is registered. Consume it only after registration so
        // requests on either side of that boundary are observed.
        val restoreRequestedDuringStartup = deleteRestoreRequest()
        if (restoreRequestedDuringStartup) {
            onRestoreRequest()
        }

        Thread(
            {
                try {
                    while (!closed.get()) {
                        val key = watcher.take()
                        key.pollEvents()
                            .asSequence()
                            .filter { event -> event.kind() == StandardWatchEventKinds.ENTRY_CREATE }
                            .mapNotNull { event -> event.context() as? Path }
                            .filter { path -> path.fileName == restoreRequestFilePath.fileName }
                            .forEach {
                                if (deleteRestoreRequest()) {
                                    onRestoreRequest()
                                }
                            }
                        if (!key.reset()) {
                            break
                        }
                    }
                } catch (_: ClosedWatchServiceException) {
                    // Closing the manager stops the watcher.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            "keyguard-single-instance-watcher",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun sendRestoreRequest() {
        Files.deleteIfExists(restoreRequestFilePath)
        try {
            Files.createFile(restoreRequestFilePath)
        } catch (_: FileAlreadyExistsException) {
            // A concurrent process already sent the same request.
        }
    }

    private fun deleteRestoreRequest(): Boolean = Files.deleteIfExists(restoreRequestFilePath)

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val ownedLock = fileLock
        watchService?.close()
        ownedLock?.release()
        fileChannel?.close()
        if (ownedLock != null) {
            deleteRestoreRequest()
        }
    }
}
