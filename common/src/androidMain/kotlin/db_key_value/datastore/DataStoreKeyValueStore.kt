package db_key_value.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.measureTimedValue

class DataStoreKeyValueStore(
    private val provideFile: suspend () -> File,
    private val createDataStore: suspend (File) -> DataStore<Preferences>,
    private val logTag: String,
    private val logRepository: LogRepository,
    private val backingStore: KeyValueStore? = null,
) : KeyValueStore, SecureKeyValueStore {
    companion object {
        private const val TAG = "DataStoreStore"
    }

    constructor (
        context: Context,
        file: String,
        logRepository: LogRepository,
        backingStore: KeyValueStore? = null,
    ) : this(
        provideFile = {
            getDataStoreFile(
                context = context,
                file = file,
            )
        },
        createDataStore = { dataStoreFile ->
            PreferenceDataStoreFactory.create {
                dataStoreFile
            }
        },
        logTag = file,
        logRepository = logRepository,
        backingStore = backingStore,
    )

    private var flowPrefs: DataStore<Preferences>? = null

    private val flowMutex = Mutex()

    private fun getFlowPrefsIo() = ioEffect { getFlowPrefs().data.toIO() }
        .flatten()

    private suspend fun getFlowPrefs(): DataStore<Preferences> =
        flowPrefs ?: flowMutex.withLock {
            flowPrefs
            // create a new instance of the preferences
                ?: performCreatePrefs().also { flowPrefs = it }
        }

    private suspend fun performCreatePrefs(): DataStore<Preferences> = withContext(Dispatchers.IO) {
        val result = measureTimedValue {
            val dataStoreFile = provideFile()
            val dataStoreInit = !dataStoreFile.exists()
            val dataStore = createDataStore(dataStoreFile)
            // Migrate the data from an older key store
            // if the new file has not existed yet.
            if (dataStoreInit) run {
                val migrationData = backingStore?.let { store ->
                    store
                        .getFile()
                        .flatMap { f ->
                            if (f.exists()) {
                                return@flatMap store.getAll()
                            }

                            return@flatMap io(emptyMap())
                        }
                        .attempt()
                        .bind()
                        .getOrNull()
                } ?: emptyMap()
                if (migrationData.isEmpty()) {
                    return@run
                }

                dataStore.updateData {
                    val mutablePref = it.toMutablePreferences()
                    migrationData.forEach { (key, value) ->
                        // Check for all of the supported types to
                        // store the data in a new store.
                        when (value) {
                            is Long -> mutablePref[longPreferencesKey(key)] = value
                            is Int -> mutablePref[intPreferencesKey(key)] = value
                            is Float -> mutablePref[floatPreferencesKey(key)] = value
                            is Boolean -> mutablePref[booleanPreferencesKey(key)] = value
                            is String -> mutablePref[stringPreferencesKey(key)] = value
                            else -> return@forEach
                        }
                    }
                    mutablePref
                }
            }

            dataStore
        }
        val msg = "Initializing store '$logTag' took ${result.duration}"
        logRepository.post(TAG, msg, level = LogLevel.INFO)
        result.value
    }

    override fun getFile(): IO<File> = provideFile

    override fun getAll(): IO<Map<String, Any?>> = getFlowPrefsIo()
        .effectMap { preferences ->
            preferences.asMap()
                .mapKeys { it.key.name }
        }

    override fun getKeys(): IO<Set<String>> = getFlowPrefsIo()
        .effectMap { preferences ->
            preferences.asMap()
                .keys
                .map { it.name }
                .toSet()
        }

    override fun getInt(key: String, defaultValue: Int): KeyValuePreference<Int> =
        DataStoreKeyValuePreference.of(::getFlowPrefs, intPreferencesKey(key), defaultValue)

    override fun getFloat(key: String, defaultValue: Float): KeyValuePreference<Float> =
        DataStoreKeyValuePreference.of(::getFlowPrefs, floatPreferencesKey(key), defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): KeyValuePreference<Boolean> =
        DataStoreKeyValuePreference.of(::getFlowPrefs, booleanPreferencesKey(key), defaultValue)

    override fun getLong(key: String, defaultValue: Long): KeyValuePreference<Long> =
        DataStoreKeyValuePreference.of(::getFlowPrefs, longPreferencesKey(key), defaultValue)

    override fun getString(key: String, defaultValue: String): KeyValuePreference<String> =
        DataStoreKeyValuePreference.of(::getFlowPrefs, stringPreferencesKey(key), defaultValue)
}