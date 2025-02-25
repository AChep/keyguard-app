package db_key_value.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.keyvalue.RealKeyValuePreference
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
        val flow = flow<T> {
            val dataStore = dataStoreProvider()
            dataStore
                .data
                .map { preferences ->
                    preferences[dataStoreKey]
                        ?: defaultValue
                }
                // This should never happen. If it does it would crash the
                // app, so instead we just fall back to the default value.
                .catch {
                    emit(defaultValue)
                }
                .distinctUntilChanged()
                .collect(this)
        }
        return flow
            .collect(collector)
    }
}