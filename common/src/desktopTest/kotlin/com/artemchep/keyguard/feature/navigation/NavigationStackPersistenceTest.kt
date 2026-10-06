package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStoreStore
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStackPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val password = "synthetic-navigation-password-7c934"
    private val safeRoute = RouteDescriptor.VaultList(
        title = "Logins",
        filter = DFilter.And(listOf(DFilter.ByFavorite, DFilter.ByType(DSecret.Type.Login))),
        sortId = "alphabetical",
        stacked = true,
    )

    @Test
    fun `secret filters are never written in either format and live routes stay intact`() = runTest {
        val backing = RecordingStore()
        val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        val filter = DFilter.ByPasswordValue(password)
        val filters = listOf(
            filter,
            DFilter.Not(filter),
            DFilter.And(listOf(DFilter.ByFavorite, filter)),
            DFilter.Or(listOf(DFilter.ByOtp, DFilter.Not(DFilter.And(listOf(filter))))),
            DFilter.ByPasswordValue(null),
            DFilter.ByPasswordValue(""),
        )
        val unsafeRoutes = filters.flatMap {
            listOf(
                RouteDescriptor.VaultList(filter = it),
                RouteDescriptor.Folders(filter = it),
                RouteDescriptor.Duplicates(filter = it),
                RouteDescriptor.Export(filter = it),
                RouteDescriptor.Watchtower(filter = it),
            )
        }
        val liveRoutes = listOf(safeRoute) + unsafeRoutes + RouteDescriptor.Settings
        for (format in NavigationStackPersistence.Format.entries) {
            persistence.save(format, mapOf("vault" to liveRoutes, "unsafe" to unsafeRoutes)).bind()
            assertEquals(
                mapOf("vault" to listOf(safeRoute, RouteDescriptor.Settings)),
                persistence.load(format),
            )
        }
        assertEquals(password, filter.value)
        assertEquals(32, liveRoutes.size)
        assertTrue(backing.writes.isNotEmpty())
        backing.writes.forEach { assertNoPassword(it) }
    }

    @Test
    fun `loading either format removes old secrets from both formats before restart`() = runTest {
        for (firstFormat in NavigationStackPersistence.Format.entries) {
            val backing = RecordingStore()
            val unsafe = RouteDescriptor.VaultList(filter = DFilter.Not(DFilter.ByPasswordValue(password)))
            val legacy = json.encodeToString<Map<String, List<RouteDescriptor>>>(
                mapOf("vault" to listOf(safeRoute, unsafe)),
            )
            assertTrue(password in legacy)
            for (format in NavigationStackPersistence.Format.entries) {
                backing.state = backing.state.put(format.key, legacy)
            }
            val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
            assertEquals(mapOf("vault" to listOf(safeRoute)), persistence.load(firstFormat))
            assertNoPassword(backing.state)

            // Alternating Apple/Compose writes must not reintroduce another key's
            // legacy bytes through a stale whole-file cache.
            for (format in NavigationStackPersistence.Format.entries) {
                persistence.save(format, mapOf("vault" to listOf(safeRoute))).bind()
                assertNoPassword(backing.state)
            }
            val restarted = NavigationStackPersistence(JsonKeyValueStore(backing), json)
            for (format in NavigationStackPersistence.Format.entries) {
                assertEquals(mapOf("vault" to listOf(safeRoute)), restarted.load(format))
            }
        }
    }

    @Test
    fun `malformed and unknown legacy routes are erased without a navigation write`() = runTest {
        val backing = RecordingStore()
        backing.state = persistentMapOf(
            "stacks" to "{malformed:$password",
            "router_stacks" to "{\"vault\":[{\"type\":\"future.route\",\"value\":\"$password\"}]}",
        )
        val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        for (format in NavigationStackPersistence.Format.entries) {
            assertEquals(emptyMap(), persistence.load(format))
        }
        assertNoPassword(backing.state)
    }

    @Test
    fun `cleanup retries disk publication even if a failed write updated the store cache`() = runTest {
        val backing = RecordingStore()
        backing.state = persistentMapOf(
            "stacks" to json.encodeToString<Map<String, List<RouteDescriptor>>>(
                mapOf("vault" to listOf(RouteDescriptor.VaultList(filter = DFilter.ByPasswordValue(password)))),
            ),
        )
        backing.failNextWriteFor = "stacks"
        val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        assertFailsWith<IllegalStateException> { persistence.load(NavigationStackPersistence.Format.Apple) }
        assertTrue(backing.state.values.any { password in it.toString() })
        assertEquals(emptyMap(), persistence.load(NavigationStackPersistence.Format.Apple))
        assertNoPassword(backing.state)
    }

    @Test
    fun `older dispatched saves cannot overwrite the latest submitted router state`() = runTest {
        val dispatcher = ReorderingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val persistence = NavigationStackPersistence(JsonKeyValueStore(), json)
            val repository = NavigationRestoreRepository(persistence, scope)
            dispatcher.runAll()
            repository.save("vault", listOf(safeRoute))
            repository.save("vault", listOf(RouteDescriptor.Settings))
            dispatcher.runLast()
            dispatcher.runAll()
            assertEquals(listOf(RouteDescriptor.Settings), repository.peek("vault"))
            assertEquals(
                mapOf("vault" to listOf(RouteDescriptor.Settings)),
                persistence.load(NavigationStackPersistence.Format.Compose),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `safe route arguments and ordering survive serialization and restart`() = runTest {
        val backing = RecordingStore()
        val safeFilter = DFilter.Not(DFilter.Or(listOf(DFilter.ByFavorite, DFilter.ByOtp)))
        val routes = listOf(
            safeRoute,
            RouteDescriptor.VaultCipherView("item-1", "account-1"),
            RouteDescriptor.Folders(safeFilter, empty = true),
            RouteDescriptor.Duplicates(safeFilter),
            RouteDescriptor.Export("Export", safeFilter),
            RouteDescriptor.Watchtower(safeFilter),
            RouteDescriptor.SendList(),
            RouteDescriptor.Generator(uris = listOf("https://example.com"), password = true),
        )
        val expected = mapOf("vault" to routes)
        val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        persistence.save(NavigationStackPersistence.Format.Apple, expected).bind()
        val restarted = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        assertEquals(expected, restarted.load(NavigationStackPersistence.Format.Apple))
    }

    @Test
    fun `a deferred save checks filters at the actual serialization boundary`() = runTest {
        val backing = RecordingStore()
        val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        val filters = mutableListOf<DFilter>(DFilter.ByFavorite)
        val save = persistence.save(
            NavigationStackPersistence.Format.Apple,
            mapOf("vault" to listOf(RouteDescriptor.VaultList(filter = DFilter.And(filters)))),
        )
        filters += DFilter.ByPasswordValue(password)
        save.bind()
        assertEquals(emptyMap(), persistence.load(NavigationStackPersistence.Format.Apple))
        assertNoPassword(backing.state)
    }

    @Test
    fun `saving while hydration is suspended preserves other routers and removes unsafe routes`() = runTest {
        val readGate = CompletableDeferred<Unit>()
        val backing = RecordingStore(readGate)
        backing.state = persistentMapOf(
            "router_stacks" to json.encodeToString<Map<String, List<RouteDescriptor>>>(
                mapOf("other" to listOf(safeRoute)),
            ),
        )
        val persistence = NavigationStackPersistence(JsonKeyValueStore(backing), json)
        val repository = NavigationRestoreRepository(persistence, this)
        runCurrent()
        repository.save("vault", listOf(safeRoute))
        repository.save("unsafe", listOf(RouteDescriptor.VaultList(filter = DFilter.ByPasswordValue(password))))
        readGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(safeRoute), repository.peek("vault"))
        assertEquals(listOf(safeRoute), repository.peek("other"))
        assertNull(repository.peek("unsafe"))
        assertNoPassword(backing.state)

        repository.save("vault", emptyList())
        advanceUntilIdle()
        assertNull(repository.peek("vault"))
        assertEquals(listOf(safeRoute), repository.peek("other"))
    }

    private fun assertNoPassword(state: Map<String, Any?>) {
        assertFalse(state.values.any { password in it.toString() })
        assertFalse(state.values.any { "by_pwd_value" in it.toString() })
    }

    private class RecordingStore(
        private val readGate: CompletableDeferred<Unit>? = null,
    ) : JsonKeyValueStoreStore {
        var state: PersistentMap<String, Any?> = persistentMapOf()
        val writes = mutableListOf<PersistentMap<String, Any?>>()
        var failNextWriteFor: String? = null

        override fun read() = ioEffect {
            readGate?.await()
            state
        }

        override fun write(state: PersistentMap<String, Any?>) = ioEffect {
            failNextWriteFor?.let { key ->
                if (state[key] != this@RecordingStore.state[key]) {
                    failNextWriteFor = null
                    error("Simulated write failure before publication")
                }
            }
            this@RecordingStore.state = state
            writes += state
        }
    }

    private class ReorderingDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runLast() = tasks.removeLast().run()

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
