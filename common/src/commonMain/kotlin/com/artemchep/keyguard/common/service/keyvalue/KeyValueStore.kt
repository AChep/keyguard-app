package com.artemchep.keyguard.common.service.keyvalue

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File

interface KeyValueStore {
    companion object {
        /**
         * Flag that marks that the migration from X to this
         * key value store has been completed.
         */
        const val HAS_MIGRATED = "__has_migrated"
    }

    fun getFile(): IO<File>

    fun getAll(): IO<Map<String, Any?>>

    fun getKeys(): IO<Set<String>>

    fun getInt(key: String, defaultValue: Int): KeyValuePreference<Int>

    fun getFloat(key: String, defaultValue: Float): KeyValuePreference<Float>

    fun getBoolean(key: String, defaultValue: Boolean): KeyValuePreference<Boolean>

    fun getLong(key: String, defaultValue: Long): KeyValuePreference<Long>

    fun getString(key: String, defaultValue: String): KeyValuePreference<String>
}

interface SecureKeyValueStore : KeyValueStore

fun <T> KeyValueStore.getObject(
    key: String,
    defaultValue: T,
    serialize: (T) -> String,
    deserialize: (String) -> T,
): KeyValuePreference<T> {
    val stringPref = getString(key, serialize(defaultValue))
    return object : KeyValuePreference<T> {
        override fun setAndCommit(value: T): IO<Unit> = ioEffect(Dispatchers.Default) {
            serialize(value)
        }
            .flatMap(stringPref::setAndCommit)

        override fun deleteAndCommit(): IO<Unit> = stringPref.deleteAndCommit()

        override suspend fun collect(collector: FlowCollector<T>) = stringPref
            .map {
                deserialize(it)
            }
            .flowOn(Dispatchers.Default)
            .collect(collector)
    }
}

inline fun <reified T> KeyValueStore.getSerializable(
    json: Json,
    key: String,
    defaultValue: T,
): KeyValuePreference<T> = getObject<T>(
    key = key,
    defaultValue = defaultValue,
    serialize = { entity ->
        if (entity == null) {
            return@getObject ""
        }

        json.encodeToString(entity)
    },
    deserialize = {
        runCatching {
            json.decodeFromString<T>(it)
        }.getOrElse {
            // Fallback to the default value
            defaultValue
        }
    },
)

inline fun <reified T : Enum<*>> KeyValueStore.getEnumNullable(
    key: String,
    crossinline lens: (T) -> String,
): KeyValuePreference<T?> = getObject(
    key,
    defaultValue = null,
    serialize = { value ->
        value?.let(lens)
            .orEmpty()
    },
    deserialize = { serializedKey ->
        T::class.java
            .enumConstants
            .firstOrNull {
                lens(it) == serializedKey
            }
    },
)
