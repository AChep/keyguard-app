package db_key_value.datastore.encrypted

import db_key_value.datastore.encrypted.exception.SecureStorageColdStartRequiredException
import db_key_value.datastore.encrypted.exception.SecureStorageInitializationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecureStorageCoordinatorTest {
    @Test
    fun `concurrent callers share one key initialization`() =
        runTest {
            val keysetStarted = CompletableDeferred<Unit>()
            val allowKeysetToFinish = CompletableDeferred<Unit>()
            var inventoryCalls = 0
            var masterKeyCalls = 0
            var keysetCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    inspectArtifacts = {
                        inventoryCalls += 1
                        freshInventory()
                    },
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        "master-key"
                    },
                    initializeKeyset = {
                        keysetCalls += 1
                        keysetStarted.complete(Unit)
                        allowKeysetToFinish.await()
                    },
                )

            val results = List(20) { async { coordinator.masterKeyAlias() } }
            keysetStarted.await()

            assertEquals(1, inventoryCalls)
            assertEquals(1, masterKeyCalls)
            assertEquals(1, keysetCalls)
            allowKeysetToFinish.complete(Unit)

            assertEquals(List(20) { "master-key" }, results.awaitAll())
        }

    @Test
    fun `cancelled waiter does not cancel shared initialization`() =
        runTest {
            val keysetStarted = CompletableDeferred<Unit>()
            val allowKeysetToFinish = CompletableDeferred<Unit>()
            var keysetCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    initializeKeyset = {
                        keysetCalls += 1
                        keysetStarted.complete(Unit)
                        allowKeysetToFinish.await()
                    },
                )

            val cancelledWaiter = async { coordinator.masterKeyAlias() }
            keysetStarted.await()
            val survivingWaiter = async { coordinator.masterKeyAlias() }

            cancelledWaiter.cancelAndJoin()
            allowKeysetToFinish.complete(Unit)

            assertEquals("master-key", survivingWaiter.await())
            assertTrue(cancelledWaiter.isCancelled)
            assertEquals(1, keysetCalls)
        }

    @Test
    fun `cancelled initialization is not latched permanently`() =
        runTest {
            var keysetCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    initializeKeyset = {
                        keysetCalls += 1
                        if (keysetCalls == 1) {
                            throw CancellationException("initialization cancelled")
                        }
                    },
                )

            assertFailsWith<CancellationException> {
                coordinator.masterKeyAlias()
            }
            assertEquals(1, keysetCalls)

            assertEquals("master-key", coordinator.masterKeyAlias())
            assertEquals(2, keysetCalls)
        }

    @Test
    fun `undecryptable artifacts are wiped and provisioning starts clean`() =
        runTest {
            var wipeAllCalls = 0
            var inventoryCalls = 0
            var masterKeyCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    inspectArtifacts = {
                        inventoryCalls += 1
                        SecureStorageArtifactInventory(
                            ciphertextStores = setOf("master_key"),
                            keysetPresent = false,
                            masterKeyPresent = false,
                        )
                    },
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        "fresh-key"
                    },
                    wipeAllArtifacts = { wipeAllCalls += 1 },
                )

            val alias = coordinator.masterKeyAlias()

            assertEquals("fresh-key", alias)
            assertEquals(1, wipeAllCalls)
            assertEquals(1, inventoryCalls)
            assertEquals(1, masterKeyCalls)
        }

    @Test
    fun `existing consistent artifacts are adopted without wiping`() =
        runTest {
            var wipeAllCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    inspectArtifacts = {
                        SecureStorageArtifactInventory(
                            ciphertextStores = setOf("settings"),
                            keysetPresent = true,
                            masterKeyPresent = true,
                        )
                    },
                    wipeAllArtifacts = { wipeAllCalls += 1 },
                )

            assertEquals("master-key", coordinator.masterKeyAlias())
            assertEquals(0, wipeAllCalls)
        }

    @Test
    fun `transient artifact inspection failure is retried`() =
        runTest {
            var inventoryCalls = 0
            val delays = mutableListOf<Long>()
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    inspectArtifacts = {
                        inventoryCalls += 1
                        if (inventoryCalls == 1) {
                            throw IOException("storage unavailable")
                        }
                        freshInventory()
                    },
                    delayForRetry = { delayMillis -> delays += delayMillis },
                )

            assertEquals("master-key", coordinator.masterKeyAlias())
            assertEquals(2, inventoryCalls)
            assertEquals(1, delays.size)
        }

    @Test
    fun `transient failure retries and then succeeds`() =
        runTest {
            var masterKeyCalls = 0
            val delays = mutableListOf<Long>()
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        if (masterKeyCalls == 1) {
                            throw ProviderException("temporarily unavailable")
                        }
                        "master-key"
                    },
                    delayForRetry = { delayMillis -> delays += delayMillis },
                )

            val alias = coordinator.masterKeyAlias()

            assertEquals("master-key", alias)
            assertEquals(2, masterKeyCalls)
            assertEquals(1, delays.size)
        }

    @Test
    fun `retry budget resets when initialization advances to another stage`() =
        runTest {
            var inventoryCalls = 0
            var masterKeyCalls = 0
            var wipeAllCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    inspectArtifacts = {
                        inventoryCalls += 1
                        if (inventoryCalls <= 2) {
                            throw IOException("storage unavailable")
                        }
                        freshInventory()
                    },
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        if (masterKeyCalls <= 2) {
                            throw GeneralSecurityException("keystore hiccup")
                        }
                        "master-key"
                    },
                    wipeAllArtifacts = { wipeAllCalls += 1 },
                )

            assertEquals("master-key", coordinator.masterKeyAlias())
            assertEquals(3, inventoryCalls)
            assertEquals(3, masterKeyCalls)
            assertEquals(0, wipeAllCalls)
        }

    @Test
    fun `ambiguous crypto failure wipes as a last resort and recovers`() =
        runTest {
            var masterKeyCalls = 0
            var wipeAllCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        // Fail the first three attempts (retry budget), succeed once wiped.
                        if (masterKeyCalls <= 3) {
                            throw GeneralSecurityException("keystore hiccup")
                        }
                        "master-key"
                    },
                    wipeAllArtifacts = { wipeAllCalls += 1 },
                )

            val alias = coordinator.masterKeyAlias()

            assertEquals("master-key", alias)
            assertEquals(1, wipeAllCalls)
            assertEquals(4, masterKeyCalls)
        }

    @Test
    fun `unrecoverable failure degrades and is not latched permanently`() =
        runTest {
            var masterKeyCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        if (masterKeyCalls == 1) {
                            // Classified as Degrade: no retry, no wipe.
                            throw IllegalStateException("out of memory")
                        }
                        "master-key"
                    },
                )

            assertFailsWith<SecureStorageInitializationException> {
                coordinator.masterKeyAlias()
            }
            assertEquals(1, masterKeyCalls)

            // The next access retries from scratch rather than replaying the cached failure.
            assertEquals("master-key", coordinator.masterKeyAlias())
            assertEquals(2, masterKeyCalls)
        }

    @Test
    fun `store payload corruption wipes only that store`() =
        runTest {
            val wiped = mutableListOf<String>()
            var openCalls = 0
            val coordinator = coordinator(scope = backgroundScope, wipeStore = { wiped += it })
            val preparedStore = Any()
            val opened = Any()
            var probeCalls = 0

            val result =
                coordinator.openStore(
                    store = "settings",
                    probe = {
                        probeCalls += 1
                        if (probeCalls == 1) {
                            throw GeneralSecurityException("bad tag", AEADBadTagException("corrupt"))
                        }
                        preparedStore
                    },
                    open = { prepared ->
                        assertSame(preparedStore, prepared)
                        openCalls += 1
                        opened
                    },
                )

            assertSame(opened, result)
            assertEquals(listOf("settings"), wiped)
            assertEquals(2, probeCalls)
            assertEquals(1, openCalls)
        }

    @Test
    fun `store payload is not opened when its corruption wipe fails`() =
        runTest {
            var openCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    wipeStore = { throw IOException("ciphertext is read-only") },
                )

            assertFailsWith<IOException> {
                coordinator.openStore(
                    store = "settings",
                    probe = {
                        throw GeneralSecurityException("bad tag", AEADBadTagException("corrupt"))
                    },
                    open = {
                        openCalls += 1
                        Any()
                    },
                )
            }

            assertEquals(0, openCalls)
        }

    @Test
    fun `shared key failure does not wipe one store and invalidates cached key material`() =
        runTest {
            var masterKeyCalls = 0
            val wiped = mutableListOf<String>()
            var openCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    getOrCreateMasterKey = {
                        masterKeyCalls += 1
                        "master-key"
                    },
                    wipeStore = { wiped += it },
                )

            val failure =
                assertFailsWith<SecureStorageInitializationException> {
                    coordinator.openStore(
                        store = "settings",
                        probe = { throw UnrecoverableKeyException("master key invalidated") },
                        open = {
                            openCalls += 1
                            Any()
                        },
                    )
                }
            assertTrue(failure.cause is UnrecoverableKeyException)

            assertTrue(wiped.isEmpty())
            assertEquals(0, openCalls)
            assertEquals(1, masterKeyCalls)

            // A later access provisions key material again instead of replaying the
            // cached alias associated with the invalidated key.
            assertEquals("master-key", coordinator.masterKeyAlias())
            assertEquals(2, masterKeyCalls)
        }

    @Test
    fun `store transient read failure retries and then opens`() =
        runTest {
            val wiped = mutableListOf<String>()
            val delays = mutableListOf<Long>()
            var probeCalls = 0
            var openCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    wipeStore = { wiped += it },
                    delayForRetry = { delays += it },
                )
            val opened = Any()

            val result =
                coordinator.openStore(
                    store = "settings",
                    probe = {
                        probeCalls += 1
                        if (probeCalls == 1) {
                            throw IOException("disk busy")
                        }
                    },
                    open = {
                        openCalls += 1
                        opened
                    },
                )

            assertSame(opened, result)
            assertTrue(wiped.isEmpty())
            assertEquals(2, probeCalls)
            assertEquals(1, delays.size)
            assertEquals(1, openCalls)
        }

    @Test
    fun `persistent store read failure degrades without opening or wiping`() =
        runTest {
            val wiped = mutableListOf<String>()
            val delays = mutableListOf<Long>()
            var probeCalls = 0
            var openCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    wipeStore = { wiped += it },
                    delayForRetry = { delays += it },
                )

            assertFailsWith<SecureStorageInitializationException> {
                coordinator.openStore(
                    store = "settings",
                    probe = {
                        probeCalls += 1
                        throw IOException("disk busy")
                    },
                    open = {
                        openCalls += 1
                        Any()
                    },
                )
            }

            assertTrue(wiped.isEmpty())
            assertEquals(3, probeCalls)
            assertEquals(2, delays.size)
            assertEquals(0, openCalls)
        }

    @Test
    fun `unknown store probe failure degrades without opening`() =
        runTest {
            var openCalls = 0
            val coordinator = coordinator(scope = backgroundScope)

            assertFailsWith<SecureStorageInitializationException> {
                coordinator.openStore(
                    store = "settings",
                    probe = { throw IllegalStateException("unexpected") },
                    open = {
                        openCalls += 1
                        Any()
                    },
                )
            }

            assertEquals(0, openCalls)
        }

    @Test
    fun `global recovery is deferred once a store is active`() =
        runTest {
            var keysetCalls = 0
            var wipeAllCalls = 0
            val coordinator =
                coordinator(
                    scope = backgroundScope,
                    initializeKeyset = {
                        keysetCalls += 1
                        if (keysetCalls > 1) {
                            throw UnrecoverableKeyException("master key invalidated")
                        }
                    },
                    wipeAllArtifacts = { wipeAllCalls += 1 },
                )

            coordinator.openStore(
                store = "master_key",
                probe = { },
                open = { Any() },
            )
            assertFailsWith<SecureStorageInitializationException> {
                coordinator.openStore(
                    store = "settings",
                    probe = { throw UnrecoverableKeyException("master key invalidated") },
                    open = { Any() },
                )
            }

            assertFailsWith<SecureStorageColdStartRequiredException> {
                coordinator.masterKeyAlias()
            }
            assertEquals(0, wipeAllCalls)
        }

    @Test
    fun `store is opened once when the payload verifies`() =
        runTest {
            var probeCalls = 0
            var openCalls = 0
            val coordinator = coordinator(scope = backgroundScope)
            val preparedStore = Any()
            val opened = Any()

            val result =
                coordinator.openStore(
                    store = "master_key",
                    probe = {
                        probeCalls += 1
                        preparedStore
                    },
                    open = { prepared ->
                        assertSame(preparedStore, prepared)
                        openCalls += 1
                        opened
                    },
                )

            assertSame(opened, result)
            assertEquals(1, probeCalls)
            assertEquals(1, openCalls)
        }

    private fun coordinator(
        scope: CoroutineScope,
        inspectArtifacts: suspend () -> SecureStorageArtifactInventory = { freshInventory() },
        getOrCreateMasterKey: suspend () -> String = { "master-key" },
        initializeKeyset: suspend (String) -> Unit = { },
        wipeAllArtifacts: suspend () -> Unit = { },
        wipeStore: suspend (String) -> Unit = { },
        delayForRetry: suspend (Long) -> Unit = { },
    ) = SecureStorageCoordinator(
        scope = scope,
        artifacts =
            object : SecureStorageArtifacts {
                override suspend fun inspect() = inspectArtifacts()

                override suspend fun getOrCreateMasterKey() = getOrCreateMasterKey.invoke()

                override suspend fun validateKeyset(masterKeyAlias: String) = initializeKeyset(masterKeyAlias)

                override suspend fun wipeAll() = wipeAllArtifacts()

                override suspend fun wipeStore(store: String) = wipeStore.invoke(store)
            },
        delayForRetry = delayForRetry,
    )

    private fun freshInventory() =
        SecureStorageArtifactInventory(
            ciphertextStores = emptySet(),
            keysetPresent = false,
            masterKeyPresent = false,
        )
}
