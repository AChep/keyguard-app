package com.artemchep.keyguard.desktop.util

import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceManagerTest {
    @Test
    fun requestDuringPrimaryStartupIsNotLost() {
        val lockFilesDir = Files.createTempDirectory("keyguard-single-instance-startup-test")
        val watcherCreationStarted = CountDownLatch(1)
        val allowWatcherCreation = CountDownLatch(1)
        val restoreRequest = CountDownLatch(1)
        val first = SingleInstanceManager(
            lockIdentifier = "keyguard-test",
            lockFilesDir = lockFilesDir,
            watchServiceFactory = {
                watcherCreationStarted.countDown()
                check(allowWatcherCreation.await(5, TimeUnit.SECONDS))
                FileSystems.getDefault().newWatchService()
            },
        )
        val second = SingleInstanceManager(
            lockIdentifier = "keyguard-test",
            lockFilesDir = lockFilesDir,
        )
        val firstStartup = CompletableFuture.supplyAsync {
            first.isSingleInstance(restoreRequest::countDown)
        }

        try {
            assertTrue(watcherCreationStarted.await(5, TimeUnit.SECONDS))
            assertFalse(second.isSingleInstance {})
            assertTrue(Files.exists(lockFilesDir.resolve("keyguard-test.restore_request")))
            allowWatcherCreation.countDown()

            assertTrue(firstStartup.get(5, TimeUnit.SECONDS))
            assertTrue(restoreRequest.await(5, TimeUnit.SECONDS))
        } finally {
            allowWatcherCreation.countDown()
            runCatching { firstStartup.get(5, TimeUnit.SECONDS) }
            second.close()
            first.close()
            lockFilesDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun secondInstanceNotifiesFirstInstance() {
        val lockFilesDir = Files.createTempDirectory("keyguard-single-instance-test")
        val first = SingleInstanceManager(
            lockIdentifier = "keyguard-test",
            lockFilesDir = lockFilesDir,
        )
        val second = SingleInstanceManager(
            lockIdentifier = "keyguard-test",
            lockFilesDir = lockFilesDir,
        )
        val third = SingleInstanceManager(
            lockIdentifier = "keyguard-test",
            lockFilesDir = lockFilesDir,
        )
        val restoreRequest = CountDownLatch(1)

        try {
            assertTrue(first.isSingleInstance(restoreRequest::countDown))
            assertTrue(first.isSingleInstance(restoreRequest::countDown))
            assertFalse(second.isSingleInstance {})
            assertTrue(restoreRequest.await(5, TimeUnit.SECONDS))
            second.close()
            assertFalse(third.isSingleInstance {})
        } finally {
            third.close()
            second.close()
            first.close()
            lockFilesDir.toFile().deleteRecursively()
        }
    }
}
