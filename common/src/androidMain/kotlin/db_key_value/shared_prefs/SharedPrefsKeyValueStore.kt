package db_key_value.shared_prefs

import android.content.Context
import android.content.SharedPreferences
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.measureTimedValue

class SharedPrefsKeyValueStore(
    private val provideFile: suspend () -> File,
    private val createPrefs: suspend () -> SharedPreferences,
    private val logTag: String,
    private val logRepository: LogRepository,
) : KeyValueStore {
    companion object {
        private const val TAG = "SharedPrefsStore"
    }

    constructor (
        context: Context,
        file: String,
        logRepository: LogRepository,
    ) : this(
        provideFile = {
            getSharedPrefsFile(
                context = context,
                file = file,
            )
        },
        createPrefs = {
            context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
        },
        logTag = file,
        logRepository = logRepository,
    )

    private var flowPrefs: FlowSharedPreferences? = null

    private val flowPrefsMutex = Mutex()

    private suspend fun getFlowPrefs(): FlowSharedPreferences =
        flowPrefs ?: flowPrefsMutex.withLock {
            flowPrefs
            // create a new instance of the preferences
                ?: performCreatePrefs().also { flowPrefs = it }
        }

    private suspend fun performCreatePrefs(
    ) = withContext(Dispatchers.IO) {
        val result = measureTimedValue {
            val prefs = createPrefs()
            FlowSharedPreferences(prefs)
        }
        val msg = "Initializing store '$logTag' took ${result.duration}"
        logRepository.post(TAG, msg, level = LogLevel.INFO)
        result.value
    }

    // Returns a file that this store is backed by. Should
    // only be used internally.
    override fun getFile(): IO<File> = provideFile

    override fun getAll(): IO<Map<String, Any?>> = {
        getFlowPrefs().sharedPreferences.all
    }

    override fun getKeys(): IO<Set<String>> = getAll()
        .map { it.keys }

    override fun getInt(key: String, defaultValue: Int): KeyValuePreference<Int> =
        SharedPrefsKeyValuePreference.of(key, defaultValue) {
            getFlowPrefs().getInt(key, defaultValue)
        }

    override fun getFloat(key: String, defaultValue: Float): KeyValuePreference<Float> =
        SharedPrefsKeyValuePreference.of(key, defaultValue) {
            getFlowPrefs().getFloat(key, defaultValue)
        }

    override fun getBoolean(key: String, defaultValue: Boolean): KeyValuePreference<Boolean> =
        SharedPrefsKeyValuePreference.of(key, defaultValue) {
            getFlowPrefs().getBoolean(key, defaultValue)
        }

    override fun getLong(key: String, defaultValue: Long): KeyValuePreference<Long> =
        SharedPrefsKeyValuePreference.of(key, defaultValue) {
            getFlowPrefs().getLong(key, defaultValue)
        }

    override fun getString(key: String, defaultValue: String): KeyValuePreference<String> =
        SharedPrefsKeyValuePreference.of(key, defaultValue) {
            getFlowPrefs().getString(key, defaultValue)
        }
}
