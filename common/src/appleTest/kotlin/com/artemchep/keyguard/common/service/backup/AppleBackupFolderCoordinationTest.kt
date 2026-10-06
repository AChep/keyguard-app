package com.artemchep.keyguard.common.service.backup

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleBackupFolderCoordinationTest {
    private val key = BackupObjectKey("snapshots/test.zip")

    @Test
    fun mutationsWaitForObjectReaders() = runBlocking(Dispatchers.Default) {
        withFolder { root, store ->
            for (operation in listOf(BackupObjectStoreOperation.Write, BackupObjectStoreOperation.Delete)) {
                store.write(key) { it.writeString("old") }
                withHeldAccess("$root/${key.value}", writing = false) { release ->
                    val mutation = startBlocked(store, operation)
                    val bytes = SystemFileSystem.source(Path(root, key.value)).buffered().use { it.readString() }
                    assertEquals("old", bytes)
                    release()
                    withTimeout(TIMEOUT_MS) { mutation.await() }
                }
                if (operation == BackupObjectStoreOperation.Write) {
                    assertEquals("new", store.read(key).use { it.readString() })
                } else {
                    assertNull(store.stat(key))
                }
            }
        }
    }

    @Test
    fun cancellationUnblocksRootCoordination() = runBlocking(Dispatchers.Default) {
        withFolder { root, store ->
            assertCancellationWhileHeld(
                root,
                store,
                listOf(BackupObjectStoreOperation.Stat, BackupObjectStoreOperation.List),
            )
        }
    }

    @Test
    fun cancellationUnblocksObjectCoordination() = runBlocking(Dispatchers.Default) {
        withFolder { root, store ->
            assertCancellationWhileHeld(
                "$root/${key.value}",
                store,
                listOf(
                    BackupObjectStoreOperation.Read,
                    BackupObjectStoreOperation.Write,
                    BackupObjectStoreOperation.Delete,
                ),
            )
        }
    }

    @Test
    fun cancelledRequestDoesNotEnterAccessor() = runBlocking(Dispatchers.Default) {
        withFolder { root, _ ->
            val access = AppleBackupFolderAccess.open(BackupStoreConfig.Local(root))
            try {
                var entered = false
                val request = launch {
                    currentCoroutineContext().cancel()
                    access.coordinate(BackupObjectStoreOperation.List) { entered = true }
                }
                request.join()
                assertTrue(request.isCancelled)
                assertFalse(entered)
            } finally {
                access.close()
            }
        }
    }

    @Test
    fun cancellationWaitsForActiveAccessorToFinish() = runBlocking(Dispatchers.Default) {
        withFolder { root, _ ->
            val access = AppleBackupFolderAccess.open(BackupStoreConfig.Local(root))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = launch(Dispatchers.IO) {
                access.coordinate(BackupObjectStoreOperation.List) {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                }
            }
            try {
                withTimeout(TIMEOUT_MS) { entered.await() }
                request.cancel()
                // The caller must not release descriptors or security scope while the accessor still runs.
                assertNull(withTimeoutOrNull(QUIET_MS) { request.join(); true })
                release.complete(Unit)
                withTimeout(TIMEOUT_MS) { request.join() }
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { request.join() }
                access.close()
            }
        }
    }

    private suspend fun assertCancellationWhileHeld(
        path: String,
        store: BackupObjectStore,
        operations: List<BackupObjectStoreOperation>,
    ) {
        for (operation in operations) {
            withHeldAccess(path, writing = true) {
                val request = startBlocked(store, operation)
                withTimeout(TIMEOUT_MS) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
            }
            // Cancellation neither mutates the object nor poisons later coordination.
            assertEquals("old", store.read(key).use { it.readString() })
        }
    }

    /** Starts [operation] and asserts that it stays blocked. */
    private suspend fun CoroutineScope.startBlocked(
        store: BackupObjectStore,
        operation: BackupObjectStoreOperation,
    ): Deferred<Unit> {
        val started = CompletableDeferred<Unit>()
        val request = async {
            started.complete(Unit)
            perform(store, operation)
        }
        withTimeout(TIMEOUT_MS) { started.await() }
        assertNull(withTimeoutOrNull(QUIET_MS) { request.await(); true })
        return request
    }

    private suspend fun perform(store: BackupObjectStore, operation: BackupObjectStoreOperation) {
        when (operation) {
            BackupObjectStoreOperation.Stat -> store.stat(key)
            BackupObjectStoreOperation.List -> store.list(BackupObjectKeyPrefix(""))
            BackupObjectStoreOperation.Read -> store.read(key).use { it.readString() }
            BackupObjectStoreOperation.Write -> store.write(key) { it.writeString("new") }
            BackupObjectStoreOperation.Delete -> store.delete(key)
            else -> error("Unsupported test operation: $operation")
        }
    }

    private suspend fun withHeldAccess(
        path: String,
        writing: Boolean,
        block: suspend CoroutineScope.(release: () -> Unit) -> Unit,
    ) = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                val accessor: (NSURL?) -> Unit = {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                }
                val coordinator = NSFileCoordinator(filePresenter = null)
                val url = NSURL.fileURLWithPath(path)
                if (writing) {
                    coordinator.coordinateWritingItemAtURL(url, 0u, error.ptr, accessor)
                } else {
                    coordinator.coordinateReadingItemAtURL(url, 0u, error.ptr, accessor)
                }
                assertNull(error.value)
            }
        }
        try {
            withTimeout(TIMEOUT_MS) { entered.await() }
            block { release.complete(Unit) }
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { holder.join() }
        }
    }

    private suspend fun withFolder(block: suspend (String, BackupObjectStore) -> Unit) =
        withAppleBackupFolder { root, store ->
            store.write(key) { it.writeString("old") }
            block(root, store)
        }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val QUIET_MS = 200L
    }
}
