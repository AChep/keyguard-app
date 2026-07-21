package db_key_value.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataStoreKeyValuePreferenceTest {
    @Test
    fun `provider initialization failure degrades to the default value`() =
        runTest {
            val preference =
                DataStoreKeyValuePreference.of(
                    dataStoreProvider = { throw IOException("storage unavailable") },
                    key = intPreferencesKey("key"),
                    defaultValue = 42,
                )

            assertEquals(42, preference.first())
        }

    @Test
    fun `provider initialization is retried by a later collection`() =
        runTest {
            val key = intPreferencesKey("key")
            val dataStore =
                object : DataStore<Preferences> {
                    override val data = flowOf(mutablePreferencesOf(key to 7))

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                        transform(mutablePreferencesOf(key to 7))
                }
            var providerCalls = 0
            val preference =
                DataStoreKeyValuePreference.of(
                    dataStoreProvider = {
                        providerCalls += 1
                        if (providerCalls == 1) {
                            throw IOException("storage unavailable")
                        }
                        dataStore
                    },
                    key = key,
                    defaultValue = 42,
                )

            assertEquals(42, preference.first())
            assertEquals(7, preference.first())
            assertEquals(2, providerCalls)
        }

    @Test
    fun `unexpected provider initialization failure is not hidden`() =
        runTest {
            val preference =
                DataStoreKeyValuePreference.of(
                    dataStoreProvider = { throw IllegalStateException("invalid setup") },
                    key = intPreferencesKey("key"),
                    defaultValue = 42,
                )

            assertFailsWith<IllegalStateException> {
                preference.first()
            }
        }
}
