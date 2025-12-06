package com.artemchep.keyguard.common.service.urlblock

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.UrlBlock
import com.artemchep.keyguard.data.UrlBlockQueries
import com.artemchep.keyguard.dataexposed.UrlBlockQueries as ExposedUrlBlockQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class UrlBlockRepositoryImpl(
    private val vaultDatabaseManager: VaultDatabaseManager,
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : UrlBlockRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        vaultDatabaseManager = directDI.instance(),
        exposedDatabaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGlobalUrlBlock>> =
        daoEffect { vaultDao, exposedDao ->
            vaultDao.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        parseEntity(entity)
                    }
            }

    private fun parseEntity(entity: UrlBlock): DGlobalUrlBlock {
        val mode = MatchDetection.entries
            .firstOrNull { it.key == entity.mode }
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

        val id = model.id
            ?.toLongOrNull()
        if (id != null) {
            // First we want to update the exposed database, as it is
            // just the clone and if the update fails the user will see
            // that the update has failed.
            if (model.exposed) {
                exposedDb.urlBlockQueries.insert(
                    id = id,
                    name = model.name,
                    description = model.description,
                    uri = model.uri,
                    mode = model.mode.key,
                    createdAt = model.createdDate,
                    enabled = model.enabled.int.toLong(),
                )
            } else {
                exposedDb.urlBlockQueries.deleteByIds(
                    ids = id,
                )
            }

            vaultDb.urlBlockQueries.update(
                id = id,
                name = model.name,
                description = model.description,
                uri = model.uri,
                mode = model.mode.key,
                createdAt = model.createdDate,
                enabled = model.enabled.int.toLong(),
                exposed = model.exposed.int.toLong(),
            )
        } else {
            val id = vaultDb.urlBlockQueries.transactionWithResult {
                vaultDb.urlBlockQueries.insert(
                    name = model.name,
                    description = model.description,
                    uri = model.uri,
                    mode = model.mode.key,
                    createdAt = model.createdDate,
                    enabled = model.enabled.int.toLong(),
                    exposed = model.exposed.int.toLong(),
                )
                vaultDb.utilQueries
                    .getLastInsertRowId()
                    .executeAsOne()
            }
            exposedDb.urlBlockQueries.insert(
                id = id,
                name = model.name,
                description = model.description,
                uri = model.uri,
                mode = model.mode.key,
                createdAt = model.createdDate,
                enabled = model.enabled.int.toLong(),
            )
        }
    }

    override fun removeAll(): IO<Unit> =
        daoEffect { vaultDao, exposedDao ->
            exposedDao.deleteAll()
            vaultDao.deleteAll()
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { vaultDao, exposedDao ->
            exposedDao.transaction {
                ids.forEach {
                    val id = it.toLongOrNull()
                        ?: return@forEach
                    exposedDao.deleteByIds(id)
                }
            }
            vaultDao.transaction {
                ids.forEach {
                    val id = it.toLongOrNull()
                        ?: return@forEach
                    vaultDao.deleteByIds(id)
                }
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (UrlBlockQueries, ExposedUrlBlockQueries) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val vaultDb = vaultDatabaseManager
            .get().bind()
        val exposedDb = exposedDatabaseManager
            .get().bind()
        block(vaultDb.urlBlockQueries, exposedDb.urlBlockQueries)
    }
}
