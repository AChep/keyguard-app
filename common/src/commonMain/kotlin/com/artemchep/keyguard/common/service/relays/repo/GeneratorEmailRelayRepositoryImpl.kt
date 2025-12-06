package com.artemchep.keyguard.common.service.relays.repo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.service.state.impl.toJson
import com.artemchep.keyguard.common.service.state.impl.toMap
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.GeneratorEmailRelayQueries
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GeneratorEmailRelayRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : GeneratorEmailRelayRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        json = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGeneratorEmailRelay>> =
        daoEffect { dao ->
            dao.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        val data = kotlin
                            .runCatching {
                                val el = json.parseToJsonElement(entity.data_)
                                el.jsonObject
                                    .toMap()
                                    .mapValues { entry ->
                                        entry.value.toString()
                                    }
                            }
                            .getOrElse {
                                emptyMap()
                            }
                            .toPersistentMap()
                        DGeneratorEmailRelay(
                            id = entity.id.toString(),
                            name = entity.name,
                            type = entity.type,
                            data = data,
                            createdDate = entity.createdAt,
                        )
                    }
            }

    override fun put(model: DGeneratorEmailRelay): IO<Unit> =
        daoEffect { dao ->
            val data = json.encodeToString(model.data.toJson())
            val id = model.id?.toLongOrNull()
            if (id != null) {
                dao.update(
                    id = id,
                    name = model.name,
                    type = model.type,
                    data = data,
                    createdAt = model.createdDate,
                )
            } else {
                dao.insert(
                    name = model.name,
                    type = model.type,
                    data = data,
                    createdAt = model.createdDate,
                )
            }
        }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { dao ->
            dao.transaction {
                ids.forEach {
                    val id = it.toLongOrNull()
                        ?: return@forEach
                    dao.deleteByIds(id)
                }
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (GeneratorEmailRelayQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.generatorEmailRelayQueries
            block(dao)
        }
}
