package com.artemchep.keyguard.common.service.keyvalue

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.platform.LocalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

interface KeyValueStore {
    fun getFile(): IO<LocalPath>

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
): VirtualKeyValuePreference<T, String> = getString(
    key = key,
    defaultValue = serialize(defaultValue),
).mapToObjectPreference(
    serialize = serialize,
    deserialize = deserialize,
)

fun <T> KeyValuePreference<String>.mapToObjectPreference(
    serialize: (T) -> String,
    deserialize: (String) -> T,
): VirtualKeyValuePreference<T, String> =
    object : VirtualKeyValuePreference<T, String> {
        override val key: String
            get() = this@mapToObjectPreference.key

        override val field: KeyValuePreference<String>
            get() = this@mapToObjectPreference

        override fun setAndCommit(value: T): IO<Unit> = ioEffect(Dispatchers.Default) {
            serialize(value)
        }
            .flatMap(field::setAndCommit)

        override fun deleteAndCommit(): IO<Unit> = field.deleteAndCommit()

        override suspend fun collect(collector: FlowCollector<T>) = field
            .map {
                deserialize(it)
            }
            .flowOn(Dispatchers.Default)
            .collect(collector)
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

inline fun <reified T> KeyValueStore.getEnumNullable(
    key: String,
    crossinline lens: (T) -> String,
): KeyValuePreference<T?> where T : Enum<T> = getObject(
    key,
    defaultValue = null,
    serialize = { value ->
        value?.let(lens)
            .orEmpty()
    },
    deserialize = { serializedKey ->
        enumValues<T>()
            .firstOrNull {
                lens(it) == serializedKey
            }
    },
)
