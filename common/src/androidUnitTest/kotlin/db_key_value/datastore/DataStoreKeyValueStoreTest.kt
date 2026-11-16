package db_key_value.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreUnavailableException
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.common.util.causeChain
import com.artemchep.keyguard.test.withTempDirectory
import com.artemchep.keyguard.util.io.toLocalPath
import db_key_value.datastore.encrypted.exception.SecureStorageInitializationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataStoreKeyValueStoreTest {
    @Test
    fun `corruption wipe does not restore a stale backing store`() =
        runTest {
            withTempDirectory("datastore-migration-test") { dir ->
                val directory = dir.toFile()
                val dataStoreFile =
                    directory
                        .resolve("settings.preferences_pb")
                        .apply { writeText("corrupt") }
                directory.resolve("settings.xml").writeText("recoverable V1 data")
                var backingReads = 0
                val backingStore =
                    object : KeyValueStore {
                        override fun getFile(): IO<LocalPath> =
                            {
                                directory.resolve("settings.xml").toLocalPath()
                            }

                        override fun getAll(): IO<Map<String, Any?>> =
                            {
                                backingReads += 1
                                mapOf("value" to 7)
                            }

                        override fun getKeys(): IO<Set<String>> = error("not used")

                        override fun getInt(
                            key: String,
                            defaultValue: Int,
                        ): KeyValuePreference<Int> = error("not used")

                        override fun getFloat(
                            key: String,
                            defaultValue: Float,
                        ): KeyValuePreference<Float> = error("not used")

                        override fun getBoolean(
                            key: String,
                            defaultValue: Boolean,
                        ): KeyValuePreference<Boolean> = error("not used")

                        override fun getLong(
                            key: String,
                            defaultValue: Long,
                        ): KeyValuePreference<Long> = error("not used")

                        override fun getString(
                            key: String,
                            defaultValue: String,
                        ): KeyValuePreference<String> = error("not used")
                    }
                val emptyDataStore =
                    object : DataStore<Preferences> {
                        override val data = flowOf(mutablePreferencesOf())

                        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                            transform(mutablePreferencesOf())
                    }
                val store =
                    DataStoreKeyValueStore(
                        provideFile = { dataStoreFile },
                        createDataStore = { file ->
                            // Simulates the secure-storage payload probe deleting a
                            // corrupt, previously initialized V2 file.
                            file.delete()
                            emptyDataStore
                        },
                        logTag = "settings",
                        logRepository = LogRepositoryBridge(emptyList()),
                        backingStore = backingStore,
                    )

                assertEquals(42, store.getInt("value", 42).first())
                assertEquals(0, backingReads)
            }
        }

    @Test
    fun `a store that must not fall back to defaults reports failing to open`() =
        runTest {
            withTempDirectory("datastore-unavailable-test") { dir ->
                val key = intPreferencesKey("value")
                val dataStore =
                    object : DataStore<Preferences> {
                        override val data = flowOf(mutablePreferencesOf(key to 7))

                        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                            transform(mutablePreferencesOf(key to 7))
                    }
                listOf(
                    SecureStorageInitializationException(),
                    IOException("storage unavailable"),
                ).forEach { failure ->
                    var opens = 0
                    val store =
                        DataStoreKeyValueStore(
                            provideFile = { dir.toFile().resolve("fingerprint.preferences_pb") },
                            createDataStore = {
                                opens += 1
                                if (opens == 1) throw failure
                                dataStore
                            },
                            logTag = "fingerprint",
                            logRepository = LogRepositoryBridge(emptyList()),
                            failWhenUnavailable = true,
                        )

                    val exception =
                        assertFailsWith<KeyValueStoreUnavailableException> {
                            store.getInt("value", 42).first()
                        }
                    assertTrue(exception.causeChain().any { it === failure })
                    // A later collection opens the store again.
                    assertEquals(7, store.getInt("value", 42).first())
                }
            }
        }
}
