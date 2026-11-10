package com.artemchep.keyguard.common.service.urlblock

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.urlblock.impl.insert
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.UrlBlock
import com.artemchep.keyguard.dataexposed.UrlBlockQueries as ExposedUrlBlockQueries
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * The blocked URIs. Every write to the vault records the change for linked apps, in the
 * same transaction, see [AppSyncRecorder]. The exposed rules are also copied to the
 * exposed database, so that autofill blocks them while the vault is locked.
 */
class UrlBlockRepositoryImpl(
    private val vaultDatabaseManager: VaultDatabaseManager,
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
    private val recorder: AppSyncRecorder = AppSyncRecorder.None,
) : UrlBlockRepository {
    override fun get(): Flow<List<DGlobalUrlBlock>> =
        daoEffect { vaultDb, _ ->
            vaultDb.urlBlockQueries.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        parseEntity(entity)
                    }
            }

    private fun parseEntity(entity: UrlBlock): DGlobalUrlBlock {
        val mode = MatchDetection.ofKey(entity.mode)
            ?: MatchDetection.Never
        return DGlobalUrlBlock(
            id = entity.id.toString(),
            name = entity.name,
            description = entity.description,
            uri = entity.uri,
            mode = mode,
            createdDate = entity.createdAt,
            enabled = entity.enabled != 0L,
            exposed = entity.exposed != 0L,
        )
    }

    override fun put(model: DGlobalUrlBlock): IO<Unit> = ioEffect(dispatcher) {
        val vaultDb = vaultDatabaseManager
            .get().bind()
        val exposedDb = exposedDatabaseManager
            .get().bind()

        val vaultDao = vaultDb.urlBlockQueries
        val existing = model.id
            ?.toLongOrNull()
            ?.let { id -> vaultDao.getById(id).executeAsOneOrNull() }
        val syncId = existing?.syncId ?: Uuid.random().toString()
        if (existing != null) {
            // First we want to update the exposed database, as it is
            // just the clone and if the update fails the user will see
            // that the update has failed.
            putExposed(exposedDb.urlBlockQueries, existing.id, model)
        }
        recorder.record(vaultDb, EntryKind.URL_BLOCK, ids = { listOf(syncId) }) {
            if (existing != null) {
                vaultDao.update(
                    id = existing.id,
                    name = model.name,
                    description = model.description,
                    uri = model.uri,
                    mode = model.mode.key,
                    createdAt = model.createdDate,
                    enabled = model.enabled.int.toLong(),
                    exposed = model.exposed.int.toLong(),
                )
            } else {
                vaultDao.insert(
                    name = model.name,
                    description = model.description,
                    uri = model.uri,
                    mode = model.mode.key,
                    createdAt = model.createdDate,
                    enabled = model.enabled.int.toLong(),
                    exposed = model.exposed.int.toLong(),
                    syncId = syncId,
                )
            }
        }
        if (existing == null) {
            // Not the last inserted row: the record of the change may come after it.
            val id = vaultDao
                .getBySyncId(syncId)
                .executeAsOne()
                .id
            putExposed(exposedDb.urlBlockQueries, id, model)
        }
    }

    /** Copies the rule [id] to the exposed database if it is exposed, or deletes its copy. */
    private fun putExposed(
        exposedDao: ExposedUrlBlockQueries,
        id: Long,
        model: DGlobalUrlBlock,
    ) {
        if (model.exposed) {
            exposedDao.insert(id = id, rule = model)
        } else {
            exposedDao.deleteByIds(
                ids = id,
            )
        }
    }

    override fun removeAll(): IO<Unit> =
        daoEffect { vaultDb, exposedDao ->
            exposedDao.deleteAll()

            val vaultDao = vaultDb.urlBlockQueries
            recorder.record(vaultDb, EntryKind.URL_BLOCK, ids = { vaultDao.getSyncIds().executeAsList() }) {
                vaultDao.deleteAll()
            }
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { vaultDb, exposedDao ->
            val localIds = ids.mapNotNull { it.toLongOrNull() }
            exposedDao.transaction {
                localIds.forEach { id ->
                    exposedDao.deleteByIds(id)
                }
            }

            val vaultDao = vaultDb.urlBlockQueries
            val syncIds = { vaultDao.getSyncIdsByIds(localIds).executeAsList() }
            recorder.record(vaultDb, EntryKind.URL_BLOCK, ids = syncIds) {
                vaultDao.deleteByIds(localIds)
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (Database, ExposedUrlBlockQueries) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val vaultDb = vaultDatabaseManager
            .get().bind()
        val exposedDb = exposedDatabaseManager
            .get().bind()
        block(vaultDb, exposedDb.urlBlockQueries)
    }
}
