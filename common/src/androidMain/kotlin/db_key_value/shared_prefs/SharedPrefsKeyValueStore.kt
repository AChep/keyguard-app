package db_key_value.shared_prefs

import android.content.Context
import android.content.SharedPreferences
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SharedPrefsKeyValueStore(
    private val createPrefs: suspend () -> SharedPreferences,
) : KeyValueStore {
    constructor (
        context: Context,
        file: String,
    ) : this(
        createPrefs = {
            context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
        },
    )

    private var flowPrefs: FlowSharedPreferences? = null

    private val flowPrefsMutex = Mutex()

    private suspend fun getFlowPrefs(): FlowSharedPreferences =
        flowPrefs ?: flowPrefsMutex.withLock {
            flowPrefs
            // create a new instance of the preferences
                ?: FlowSharedPreferences(createPrefs()).also { flowPrefs = it }
        }

    override fun getAll(): IO<Map<String, Any?>> = {
        getFlowPrefs().sharedPreferences.all
    }

    override fun getKeys(): IO<Set<String>> = getAll()
        .map { it.keys }

    override fun getInt(key: String, defaultValue: Int): KeyValuePreference<Int> =
        SharedPrefsKeyValuePreference(key, defaultValue) {
            getFlowPrefs().getInt(key, defaultValue)
        }

    override fun getFloat(key: String, defaultValue: Float): KeyValuePreference<Float> =
        SharedPrefsKeyValuePreference(key, defaultValue) {
            getFlowPrefs().getFloat(key, defaultValue)
        }

    override fun getBoolean(key: String, defaultValue: Boolean): KeyValuePreference<Boolean> =
        SharedPrefsKeyValuePreference(key, defaultValue) {
            getFlowPrefs().getBoolean(key, defaultValue)
        }

    override fun getLong(key: String, defaultValue: Long): KeyValuePreference<Long> =
        SharedPrefsKeyValuePreference(key, defaultValue) {
            getFlowPrefs().getLong(key, defaultValue)
        }

    override fun getString(key: String, defaultValue: String): KeyValuePreference<String> =
        SharedPrefsKeyValuePreference(key, defaultValue) {
            getFlowPrefs().getString(key, defaultValue)
        }
}
