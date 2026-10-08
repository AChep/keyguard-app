package com.artemchep.keyguard.common.service.keyvalue.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.atomic.AchievedSyncLevel
import com.artemchep.keyguard.util.io.atomic.AtomicCleanupIncompleteException
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicFileWriteException
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationState
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JsonKeyValueStoreCommitTest {
    @Test
    fun `cancelled first write can be retried when no preferences file was created`() = runTest {
        val root = createTempDirectory("json-store-cancelled-first-write")
        try {
            val backing = FileJsonKeyValueStoreStore(
                fileIo = {
                    AtomicFileDestination(
                        root = root.toLocalPath(),
                        relativePath = AtomicRelativePath.fromComponents(AtomicPathComponent.parse("preferences.json")),
                    )
                },
                json = Json,
            )
            val entered = CompletableDeferred<Unit>()
            var beforeWrite: suspend () -> Unit = { entered.complete(Unit); awaitCancellation() }
            val store = JsonKeyValueStore(object : JsonKeyValueStoreStore by backing {
                override fun write(state: PersistentMap<String, Any?>): IO<Unit> = {
                    beforeWrite()
                    backing.write(state)()
                }
            })
            val preference = store.getString("key", "")
            val cancelledWrite = launch { preference.setAndCommit("cancelled")() }
            entered.await()
            cancelledWrite.cancelAndJoin()
            assertEquals(emptyMap(), store.getAll()())

            beforeWrite = {}
            preference.setAndCommit("saved")()
            assertEquals("saved", preference.first())
            assertEquals("saved", JsonKeyValueStore(backing).getString("key", "").first())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing atomic root does not publish an unsaved fingerprint and retry persists`() = runTest {
        val parent = createTempDirectory("json-store-missing-root")
        try {
            val root = parent.resolve("Application Support")
            val backing = FileJsonKeyValueStoreStore(
                fileIo = {
                    AtomicFileDestination(
                        root = root.toLocalPath(),
                        relativePath = AtomicRelativePath.fromComponents(
                            AtomicPathComponent.parse("Keyguard"),
                            AtomicPathComponent.parse("fingerprint.json"),
                        ),
                    )
                },
                json = Json,
            )
            val store = JsonKeyValueStore(backing)
            val fingerprint = store.getString("fingerprint", "")
            assertFailsWith<AtomicFileWriteException> { fingerprint.setAndCommit("saved")() }
            assertEquals("", fingerprint.first())
            assertEquals("", JsonKeyValueStore(backing).getString("fingerprint", "").first())

            root.toFile().mkdir()
            fingerprint.setAndCommit("saved")()
            assertEquals("saved", fingerprint.first())
            assertEquals("saved", JsonKeyValueStore(backing).getString("fingerprint", "").first())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `observers see only committed values and concurrent updates do not overwrite each other`() = runTest {
        val backing = Backing()
        val store = JsonKeyValueStore(backing)
        val first = store.getString("first", "")
        val observed = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { first.collect { observed += it } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backing.beforeWrite = { entered.complete(Unit); release.await() }
        val write = launch { first.setAndCommit("one")() }
        entered.await()
        val second = launch { store.getString("second", "").setAndCommit("two")() }
        runCurrent()
        assertEquals(listOf(""), observed)
        assertEquals(1, backing.writes)
        release.complete(Unit)
        write.join()
        second.join()
        assertEquals(listOf("", "one"), observed)
        assertEquals(mapOf<String, Any?>("first" to "one", "second" to "two"), backing.state)
        assertEquals(backing.state, store.getAll()())
    }

    @Test
    fun `clear waits for an in flight update and leaves disk and memory empty`() = runTest {
        val backing = Backing()
        val store = JsonKeyValueStore(backing)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backing.beforeWrite = { entered.complete(Unit); release.await() }
        val write = launch { store.getString("key", "").setAndCommit("value")() }
        entered.await()
        val clear = launch { store.clearAndCommit()() }
        runCurrent()
        assertEquals(1, backing.writes)
        release.complete(Unit)
        write.join()
        clear.join()
        assertTrue(backing.state.isEmpty())
        assertEquals(backing.state, store.getAll()())
    }

    @Test
    fun `failed update delete and clear retain the committed values`() = runTest {
        val backing = Backing()
        val store = JsonKeyValueStore(backing)
        val pref = store.getString("key", "")
        pref.setAndCommit("old")()
        backing.beforeWrite = { throw failure(AtomicPublicationState.NotPublished) }
        for (operation in listOf(pref.setAndCommit("new"), pref.deleteAndCommit(), store.clearAndCommit())) {
            assertFailsWith<AtomicFileWriteException> { operation() }
            assertEquals("old", pref.first())
            assertEquals(backing.state, store.getAll()())
        }
    }

    @Test
    fun `post publication failures reconcile disk while preserving the original error`() = runTest {
        val errors = listOf(
            failure(AtomicPublicationState.PublishedSyncUnknown),
            failure(AtomicPublicationState.Unknown),
            AtomicCleanupIncompleteException(
                "cleanup",
                achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
                cleanupFailure = null,
            ),
            CancellationException("cancelled after publication"),
        )
        for (error in errors) {
            val backing = Backing()
            val store = JsonKeyValueStore(backing)
            backing.afterWrite = { throw error }
            val caught = runCatching { store.getString("key", "").setAndCommit("new")() }.exceptionOrNull()
            assertSame(error, caught)
            assertEquals("new", store.getString("key", "").first())
            assertEquals(backing.state, store.getAll()())
        }
    }

    @Test
    fun `unknown publication without a disk change does not publish the attempted value`() = runTest {
        val backing = Backing()
        val store = JsonKeyValueStore(backing)
        backing.beforeWrite = { throw failure(AtomicPublicationState.Unknown) }
        assertFailsWith<AtomicFileWriteException> { store.getString("key", "").setAndCommit("new")() }
        assertEquals("", store.getString("key", "").first())
    }

    @Test
    fun `failed reconciliation blocks later writes until committed data can be reloaded`() = runTest {
        val backing = Backing()
        val store = JsonKeyValueStore(backing)
        backing.afterWrite = {
            backing.beforeRead = { error("unreadable") }
            throw failure(AtomicPublicationState.Unknown)
        }
        assertFailsWith<AtomicFileWriteException> { store.getString("first", "").setAndCommit("one")() }
        backing.afterWrite = {}
        assertFailsWith<IllegalStateException> { store.getString("second", "").setAndCommit("two")() }
        assertEquals(1, backing.writes)
        backing.beforeRead = {}
        store.getString("second", "").setAndCommit("two")()
        assertEquals(mapOf<String, Any?>("first" to "one", "second" to "two"), backing.state)
        assertEquals(backing.state, store.getAll()())
    }

    private fun failure(state: AtomicPublicationState) = AtomicFileWriteException(
        message = "injected write failure",
        publicationState = state,
        failure = FileSystemFailure(FileSystemFailureKind.Other),
    )

    private class Backing : JsonKeyValueStoreStore {
        var state = persistentMapOf<String, Any?>()
        var writes = 0
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        override fun read(): IO<PersistentMap<String, Any?>> = { beforeRead(); state }
        override fun write(state: PersistentMap<String, Any?>): IO<Unit> = {
            writes += 1
            beforeWrite()
            this.state = state
            afterWrite()
        }
    }
}
