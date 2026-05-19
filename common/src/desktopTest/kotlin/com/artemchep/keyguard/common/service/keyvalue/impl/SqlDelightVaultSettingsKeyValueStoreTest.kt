package com.artemchep.keyguard.common.service.keyvalue.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightVaultSettingsKeyValueStoreTest {
    @Test
    fun `missing values use defaults`() = runTest {
        val store = createStore(UnconfinedTestDispatcher(testScheduler)).store

        assertEquals(1, store.getInt("int", 1).first())
        assertEquals(1.5f, store.getFloat("float", 1.5f).first())
        assertEquals(true, store.getBoolean("boolean", true).first())
        assertEquals(2L, store.getLong("long", 2L).first())
        assertEquals("default", store.getString("string", "default").first())
    }

    @Test
    fun `set stores all primitive values and exposes keys`() = runTest {
        val store = createStore(UnconfinedTestDispatcher(testScheduler)).store

        store.getInt("int", 0).setAndCommit(7)()
        store.getFloat("float", 0f).setAndCommit(2.5f)()
        store.getBoolean("boolean", false).setAndCommit(true)()
        store.getLong("long", 0L).setAndCommit(99L)()
        store.getString("string", "").setAndCommit("value")()

        assertEquals(7, store.getInt("int", 0).first())
        assertEquals(2.5f, store.getFloat("float", 0f).first())
        assertEquals(true, store.getBoolean("boolean", false).first())
        assertEquals(99L, store.getLong("long", 0L).first())
        assertEquals("value", store.getString("string", "").first())
        assertEquals(
            mapOf(
                "int" to 7,
                "float" to 2.5f,
                "boolean" to true,
                "long" to 99L,
                "string" to "value",
            ),
            store.getAll()(),
        )
        assertEquals(
            setOf("int", "float", "boolean", "long", "string"),
            store.getKeys()(),
        )
    }

    @Test
    fun `delete removes value and falls back to default`() = runTest {
        val store = createStore(UnconfinedTestDispatcher(testScheduler)).store
        val pref = store.getBoolean("enabled", true)

        pref.setAndCommit(false)()
        assertEquals(false, pref.first())

        pref.deleteAndCommit()()
        assertEquals(true, pref.first())
        assertEquals(emptyMap(), store.getAll()())
        assertEquals(emptySet(), store.getKeys()())
    }

    @Test
    fun `wrong type and invalid values fall back to defaults`() = runTest {
        val testStore = createStore(UnconfinedTestDispatcher(testScheduler))
        val database = testStore.database
        val store = testStore.store

        database.vaultSettingQueries.upsert(
            id = "flag",
            type = "long",
            value = "1",
        )
        database.vaultSettingQueries.upsert(
            id = "bad-int",
            type = "int",
            value = "not-an-int",
        )
        database.vaultSettingQueries.upsert(
            id = "unknown",
            type = "object",
            value = "{}",
        )

        assertEquals(false, store.getBoolean("flag", false).first())
        assertEquals(42, store.getInt("bad-int", 42).first())
        assertEquals(
            mapOf("flag" to 1L),
            store.getAll()(),
        )
        assertEquals(
            setOf("flag", "bad-int", "unknown"),
            store.getKeys()(),
        )
    }

    @Test
    fun `preference flow emits defaults and updates`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = createStore(dispatcher).store
        val pref = store.getString("name", "missing")
        val values = mutableListOf<String>()

        val job = launch(dispatcher) {
            pref
                .take(3)
                .toList(values)
        }

        pref.setAndCommit("alice")()
        pref.setAndCommit("bob")()
        job.join()

        assertEquals(listOf("missing", "alice", "bob"), values)
    }

    @Test
    fun `get file is unsupported`() = runTest {
        val store = createStore(UnconfinedTestDispatcher(testScheduler)).store

        assertFailsWith<UnsupportedOperationException> {
            store.getFile()()
        }
    }

    private fun createStore(
        dispatcher: CoroutineContext,
    ): TestStore {
        val database = createUploadTestDatabase()
        val manager = TestVaultDatabaseManager(database)
        val store = SqlDelightVaultSettingsKeyValueStore(
            databaseManager = manager,
            dispatcher = dispatcher,
        )
        return TestStore(
            database = database,
            store = store,
        )
    }

    private data class TestStore(
        val database: Database,
        val store: SqlDelightVaultSettingsKeyValueStore,
    )

    private class TestVaultDatabaseManager(
        private val database: Database,
    ) : VaultDatabaseManager {
        override fun get(): IO<Database> = io(database)

        override fun <T> mutate(
            tag: String,
            block: suspend (Database) -> T,
        ): IO<T> = ioEffect {
            block(database)
        }

        override fun changePassword(newMasterKey: MasterKey): IO<Unit> = ioUnit()
    }
}
