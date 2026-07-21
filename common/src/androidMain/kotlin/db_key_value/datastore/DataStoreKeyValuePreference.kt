package db_key_value.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.keyvalue.RealKeyValuePreference
import db_key_value.datastore.encrypted.exception.SecureStorageInitializationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import kotlin.reflect.KClass

class DataStoreKeyValuePreference<T : Any>(
    override val clazz: KClass<T>,
    private val dataStoreProvider: suspend () -> DataStore<Preferences>,
    private val dataStoreKey: Preferences.Key<T>,
    private val defaultValue: T,
) : RealKeyValuePreference<T> {
    companion object {
        /* Only primitive types are supported! */
        inline fun <reified T : Any> of(
            noinline dataStoreProvider: suspend () -> DataStore<Preferences>,
            key: Preferences.Key<T>,
            defaultValue: T,
        ): DataStoreKeyValuePreference<T> = DataStoreKeyValuePreference(
            clazz = T::class,
            dataStoreProvider = dataStoreProvider,
            dataStoreKey = key,
            defaultValue = defaultValue,
        )
    }

    override val key: String get() = dataStoreKey.name

    override fun setAndCommit(value: T): IO<Unit> = modifyAndCommit {
        set(dataStoreKey, value)
    }

    override fun deleteAndCommit(): IO<Unit> = modifyAndCommit {
        remove(dataStoreKey)
    }

    private inline fun modifyAndCommit(
        crossinline block: MutablePreferences.() -> Unit,
    ): IO<Unit> = {
        dataStoreProvider()
            .updateData { preferences ->
                preferences
                    .toMutablePreferences()
                    .apply {
                        block()
                    }
            }
    }

    override suspend fun collect(collector: FlowCollector<T>) {
        flow {
            val dataStore = try {
                dataStoreProvider()
            } catch (throwable: Throwable) {
                when (throwable) {
                    is IOException,
                    is SecureStorageInitializationException,
                        -> {
                        // Expected storage initialization failures degrade to the
                        // default for this collection. A later collection retries
                        // the provider from scratch.
                        emit(defaultValue)
                        return@flow
                    }

                    else -> {
                        throw throwable
                    }
                }
            }
            dataStore
                .data
                .map { preferences ->
                    preferences[dataStoreKey]
                        ?: defaultValue
                }
                // Preserve the existing read-time fallback. Provider/setup failures are
                // handled separately above so unexpected configuration errors still escape.
                .catch {
                    emit(defaultValue)
                }
                .collect(this)
        }
            .distinctUntilChanged()
            .collect(collector)
    }
}
