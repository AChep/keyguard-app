package com.artemchep.keyguard.common.service.keyvalue.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.RealKeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.data.VaultSetting
import com.artemchep.keyguard.platform.LocalPath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

class SqlDelightVaultSettingsKeyValueStore(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineContext,
) : VaultSettingsKeyValueStore {
    companion object {
        private const val TAG = "VaultSettingsKeyValueStore"

        private const val TYPE_INT = "int"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_LONG = "long"
        private const val TYPE_STRING = "string"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    private class Codec<T : Any>(
        val type: String,
        val clazz: KClass<T>,
        val encode: (T) -> String,
        val decode: (String) -> T?,
    )

    private class Preference<T : Any>(
        override val key: String,
        override val clazz: KClass<T>,
        private val defaultValue: T,
        private val codec: Codec<T>,
        private val databaseManager: VaultDatabaseManager,
        private val dispatcher: CoroutineContext,
    ) : RealKeyValuePreference<T> {
        override fun setAndCommit(value: T): IO<Unit> =
            databaseManager.mutate(TAG) { db ->
                db.vaultSettingQueries.upsert(
                    id = key,
                    type = codec.type,
                    value = codec.encode(value),
                )
            }

        override fun deleteAndCommit(): IO<Unit> =
            databaseManager.mutate(TAG) { db ->
                db.vaultSettingQueries.deleteByKey(key)
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<T>) {
            flow {
                val database = databaseManager.get()
                    .bind()
                emit(database.vaultSettingQueries.getByKey(key))
            }
                .flatMapLatest { query ->
                    query
                        .asFlow()
                        .mapToOneOrNull(dispatcher)
                }
                .map { row ->
                    row?.takeIf { it.type == codec.type }
                        ?.value_
                        ?.let(codec.decode)
                        ?: defaultValue
                }
                .catch {
                    emit(defaultValue)
                }
                .distinctUntilChanged()
                .collect(collector)
        }
    }

    override fun getFile(): IO<LocalPath> = ioRaise(
        UnsupportedOperationException("Vault settings store is backed by the vault database."),
    )

    override fun getAll(): IO<Map<String, Any?>> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            db.vaultSettingQueries
                .getAll()
                .executeAsList()
                .mapNotNull { row ->
                    val value = row.decodeAny()
                        ?: return@mapNotNull null
                    row.id to value
                }
                .toMap()
        }

    override fun getKeys(): IO<Set<String>> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            db.vaultSettingQueries
                .getKeys()
                .executeAsList()
                .toSet()
        }

    override fun getInt(key: String, defaultValue: Int): KeyValuePreference<Int> =
        getPreference(
            key = key,
            defaultValue = defaultValue,
            codec = Codec(
                type = TYPE_INT,
                clazz = Int::class,
                encode = Int::toString,
                decode = String::toIntOrNull,
            ),
        )

    override fun getFloat(key: String, defaultValue: Float): KeyValuePreference<Float> =
        getPreference(
            key = key,
            defaultValue = defaultValue,
            codec = Codec(
                type = TYPE_FLOAT,
                clazz = Float::class,
                encode = Float::toString,
                decode = String::toFloatOrNull,
            ),
        )

    override fun getBoolean(key: String, defaultValue: Boolean): KeyValuePreference<Boolean> =
        getPreference(
            key = key,
            defaultValue = defaultValue,
            codec = Codec(
                type = TYPE_BOOLEAN,
                clazz = Boolean::class,
                encode = Boolean::toString,
                decode = ::decodeBoolean,
            ),
        )

    override fun getLong(key: String, defaultValue: Long): KeyValuePreference<Long> =
        getPreference(
            key = key,
            defaultValue = defaultValue,
            codec = Codec(
                type = TYPE_LONG,
                clazz = Long::class,
                encode = Long::toString,
                decode = String::toLongOrNull,
            ),
        )

    override fun getString(key: String, defaultValue: String): KeyValuePreference<String> =
        getPreference(
            key = key,
            defaultValue = defaultValue,
            codec = Codec(
                type = TYPE_STRING,
                clazz = String::class,
                encode = { it },
                decode = { it },
            ),
        )

    private fun <T : Any> getPreference(
        key: String,
        defaultValue: T,
        codec: Codec<T>,
    ): KeyValuePreference<T> = Preference(
        key = key,
        clazz = codec.clazz,
        defaultValue = defaultValue,
        codec = codec,
        databaseManager = databaseManager,
        dispatcher = dispatcher,
    )

    private fun VaultSetting.decodeAny(): Any? = when (type) {
        TYPE_INT -> value_.toIntOrNull()
        TYPE_FLOAT -> value_.toFloatOrNull()
        TYPE_BOOLEAN -> decodeBoolean(value_)
        TYPE_LONG -> value_.toLongOrNull()
        TYPE_STRING -> value_
        else -> null
    }

    private fun decodeBoolean(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
