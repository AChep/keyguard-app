package com.artemchep.keyguard.common.service.relays.repo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.state.impl.toJson
import com.artemchep.keyguard.common.service.state.impl.toMap
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * The email forwarders. Every write records the change for linked apps, in the same
 * transaction, see [AppSyncRecorder].
 */
class GeneratorEmailRelayRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
    private val recorder: AppSyncRecorder = AppSyncRecorder.None,
) : GeneratorEmailRelayRepository {
    override fun get(): Flow<List<DGeneratorEmailRelay>> =
        databaseEffect { db ->
            db.generatorEmailRelayQueries.get(1000)
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
        databaseEffect { db ->
            val dao = db.generatorEmailRelayQueries
            val data = json.encodeToString(model.data.toJson())
            db.transaction {
                val existing = model.id
                    ?.toLongOrNull()
                    ?.let { id -> dao.getById(id).executeAsOneOrNull() }
                val syncId = existing?.syncId ?: Uuid.random().toString()
                recorder.record(db, EntryKind.EMAIL_FORWARDER, ids = { listOf(syncId) }) {
                    if (existing != null) {
                        dao.update(
                            id = existing.id,
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
                            syncId = syncId,
                        )
                    }
                }
            }
        }

    override fun removeAll(): IO<Unit> =
        databaseEffect { db ->
            val dao = db.generatorEmailRelayQueries
            recorder.record(db, EntryKind.EMAIL_FORWARDER, ids = { dao.getSyncIds().executeAsList() }) {
                dao.deleteAll()
            }
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        databaseEffect { db ->
            val dao = db.generatorEmailRelayQueries
            val localIds = ids.mapNotNull { it.toLongOrNull() }
            recorder.record(db, EntryKind.EMAIL_FORWARDER, ids = { dao.getSyncIdsByIds(localIds).executeAsList() }) {
                dao.deleteByIds(localIds)
            }
        }

    private inline fun <T> databaseEffect(
        crossinline block: suspend (Database) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db)
        }
}
