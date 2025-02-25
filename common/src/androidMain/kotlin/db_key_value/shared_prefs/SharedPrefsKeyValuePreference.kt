package db_key_value.shared_prefs

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.keyvalue.RealKeyValuePreference
import com.fredporciuncula.flow.preferences.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

class SharedPrefsKeyValuePreference<T : Any>(
    override val key: String,
    override val clazz: KClass<*>,
    private val defaultValue: T,
    private val prefFactory: suspend () -> Preference<T>,
) : RealKeyValuePreference<T> {
    companion object {
        /* Only primitive types are supported! */
        inline fun <reified T : Any> of(
            key: String,
            defaultValue: T,
            noinline prefFactory: suspend () -> Preference<T>,
        ) = SharedPrefsKeyValuePreference(
            key = key,
            clazz = T::class,
            defaultValue = defaultValue,
            prefFactory = prefFactory,
        )
    }

    private var pref: Preference<T>? = null

    private val prefMutex = Mutex()

    private suspend fun getPref(): Preference<T> =
        pref ?: prefMutex.withLock {
            pref ?: prefFactory().also { pref = it }
        }

    override fun setAndCommit(value: T): IO<Unit> = ioEffect(Dispatchers.IO) {
        getPref().setAndCommit(value)
    }

    override fun deleteAndCommit(): IO<Unit> = ioEffect(Dispatchers.IO) {
        getPref().deleteAndCommit()
    }

    override suspend fun collect(collector: FlowCollector<T>) {
        flowOfPref()
            // This should never happen. If it does it would crash the
            // app, so instead we just fall back to the default value.
            .catch {
                emit(defaultValue)
            }
            .distinctUntilChanged()
            .collect(collector)
    }

    private fun flowOfPref() =
        pref?.asFlow()
        // The version that calls delegate on the
        // IO dispatcher.
            ?: flow {
                withContext(Dispatchers.IO) {
                    getPref()
                }
                    .asFlow()
                    .collect {
                        emit(it)
                    }
            }
}
