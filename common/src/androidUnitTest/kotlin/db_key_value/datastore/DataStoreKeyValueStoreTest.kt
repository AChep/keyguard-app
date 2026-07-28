package db_key_value.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DataStoreKeyValueStoreTest {
    @Test
    fun `corruption wipe does not restore a stale backing store`() =
        runTest {
            val directory = createTempDirectory("datastore-migration-test").toFile()
            try {
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
                        logRepository = NoOpLogRepository,
                        backingStore = backingStore,
                    )

                assertEquals(42, store.getInt("value", 42).first())
                assertEquals(0, backingReads)
            } finally {
                directory.deleteRecursively()
            }
        }

    private object NoOpLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }
}
