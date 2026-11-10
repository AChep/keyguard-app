package com.artemchep.keyguard.common.service.urlblock.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.dataexposed.UrlBlock as ExposedUrlBlock
import com.artemchep.keyguard.dataexposed.UrlBlockQueries as ExposedUrlBlockQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UrlBlockRepositoryExposed(
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) {
    fun get(): Flow<List<DGlobalUrlBlock>> =
        daoEffect { exposedDao ->
            exposedDao.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        parseEntity(entity)
                    }
            }

    /**
     * Replaces the exposed copy with the [rules], each one under its
     * local vault id. A rule without a local id is skipped.
     */
    fun replaceAll(
        rules: List<DGlobalUrlBlock>,
    ): IO<Unit> = exposedDatabaseManager.mutate("UrlBlockRepositoryExposed.replaceAll") { db ->
        val dao = db.urlBlockQueries
        dao.transaction {
            dao.deleteAll()
            rules.forEach { rule ->
                val id = rule.id?.toLongOrNull()
                    ?: return@forEach
                dao.insert(id = id, rule = rule)
            }
        }
    }

    private fun parseEntity(entity: ExposedUrlBlock): DGlobalUrlBlock {
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
            exposed = true,
        )
    }

    private inline fun <T> daoEffect(
        crossinline block: suspend (ExposedUrlBlockQueries) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val exposedDb = exposedDatabaseManager
            .get().bind()
        block(exposedDb.urlBlockQueries)
    }
}

/** Inserts the [rule] under the local vault [id], or updates the copy that has it. */
internal fun ExposedUrlBlockQueries.insert(
    id: Long,
    rule: DGlobalUrlBlock,
) {
    insert(
        id = id,
        name = rule.name,
        description = rule.description,
        uri = rule.uri,
        mode = rule.mode.key,
        createdAt = rule.createdDate,
        enabled = rule.enabled.int.toLong(),
    )
}
