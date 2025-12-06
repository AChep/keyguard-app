package com.artemchep.keyguard.common.service.urlblock.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.dataexposed.UrlBlockQueries as ExposedUrlBlockQueries
import com.artemchep.keyguard.dataexposed.UrlBlock as ExposedUrlBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class UrlBlockRepositoryExposed(
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        exposedDatabaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

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

    private fun parseEntity(entity: ExposedUrlBlock): DGlobalUrlBlock {
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
