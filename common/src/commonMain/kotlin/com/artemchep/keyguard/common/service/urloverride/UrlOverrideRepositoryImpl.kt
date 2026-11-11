package com.artemchep.keyguard.common.service.urloverride

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.appsync.recordPut
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The URL overrides. Every write records the change for linked apps, in the same
 * transaction, see [AppSyncRecorder]. The built-in override has no sync id, so its
 * changes stay on this device.
 */
class UrlOverrideRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
    private val recorder: AppSyncRecorder = AppSyncRecorder.None,
) : UrlOverrideRepository {
    override fun get(): Flow<List<DGlobalUrlOverride>> =
        databaseEffect { db ->
            db.urlOverrideQueries.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        val regex = entity.regex.toRegex()
                        DGlobalUrlOverride(
                            id = entity.id.toString(),
                            name = entity.name,
                            regex = regex,
                            command = entity.command,
                            createdDate = entity.createdAt,
                            enabled = entity.enabled != 0L,
                        )
                    }
            }

    override fun put(model: DGlobalUrlOverride): IO<Unit> =
        databaseEffect { db ->
            val dao = db.urlOverrideQueries
            val regex = model.regex.toString()
            val enabled = model.enabled.int.toLong()
            recorder.recordPut(
                database = db,
                kind = EntryKind.URL_OVERRIDE,
                find = { model.id?.toLongOrNull()?.let { id -> dao.getById(id).executeAsOneOrNull() } },
                // The built-in override has none: its changes stay on this device.
                syncIdOf = { it.syncId },
                update = { existing ->
                    dao.update(
                        id = existing.id,
                        name = model.name,
                        regex = regex,
                        command = model.command,
                        createdAt = model.createdDate,
                        enabled = enabled,
                    )
                },
                insert = { syncId ->
                    dao.insert(
                        name = model.name,
                        regex = regex,
                        command = model.command,
                        createdAt = model.createdDate,
                        enabled = enabled,
                        syncId = syncId,
                    )
                },
            )
        }

    override fun removeAll(): IO<Unit> =
        databaseEffect { db ->
            val dao = db.urlOverrideQueries
            recorder.record(db, EntryKind.URL_OVERRIDE, ids = { dao.getSyncIds().executeAsList() }) {
                dao.deleteAll()
            }
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        databaseEffect { db ->
            val dao = db.urlOverrideQueries
            val localIds = ids.mapNotNull { it.toLongOrNull() }
            recorder.record(db, EntryKind.URL_OVERRIDE, ids = { dao.getSyncIdsByIds(localIds).executeAsList() }) {
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
