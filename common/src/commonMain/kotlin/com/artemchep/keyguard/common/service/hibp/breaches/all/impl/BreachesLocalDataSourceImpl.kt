package com.artemchep.keyguard.common.service.hibp.breaches.all.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.model.LocalBreachesEntity
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BreachesLocalDataSourceImpl(
    private val store: KeyValueStore,
    private val json: Json,
    // passwords
    private val passwordPwnageDataSourceLocal: PasswordPwnageDataSourceLocal,
) : BreachesLocalDataSource {
    companion object {
        private const val KEY_LAST_BREACHES = "last_breaches"
    }

    private val lastBreachesPref = store
        .getObject(
            key = KEY_LAST_BREACHES,
            defaultValue = null,
            serialize = { key ->
                if (key == null) {
                    return@getObject ""
                }

                json.encodeToString(key)
            },
            deserialize = { data ->
                if (data.isBlank()) {
                    return@getObject null
                }
                // If any exception happens, we just assume that the data
                // is not present at all. Note: this implicitly clears
                // the local data of the user.
                kotlin.runCatching {
                    json.decodeFromString<LocalBreachesEntity>(data)
                }.getOrNull()
            },
        )

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.BREACHES),
        json = directDI.instance(),
        passwordPwnageDataSourceLocal = directDI.instance(),
    )

    override fun put(
        entity: LocalBreachesEntity?,
    ): IO<Unit> = ioEffect {
        val hasChanged = kotlin.run {
            val old = get().firstOrNull()
            old?.model?.breaches?.size != entity?.model?.breaches?.size ||
                    old?.getLatestDate() != entity?.getLatestDate()
        }
        if (hasChanged) {
            passwordPwnageDataSourceLocal.clear()
                .bind()
            lastBreachesPref
                .setAndCommit(entity)
                .bind()
        }
    }

    override fun get(): Flow<LocalBreachesEntity?> = lastBreachesPref

    override fun getLatestDate(): Flow<LocalDate?> = get()
        .map { entity ->
            // Get the latest date of the breach.
            entity?.getLatestDate()
        }

    private fun LocalBreachesEntity.getLatestDate() = model.breaches
        .asSequence()
        .mapNotNull { it.addedDate }
        .maxOrNull()

    override fun clear(): IO<Unit> = put(null)
}