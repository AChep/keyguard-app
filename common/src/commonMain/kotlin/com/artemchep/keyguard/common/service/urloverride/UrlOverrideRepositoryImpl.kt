package com.artemchep.keyguard.common.service.urloverride

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.UrlOverrideQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class UrlOverrideRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : UrlOverrideRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGlobalUrlOverride>> =
        daoEffect { dao ->
            dao.get(1000)
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
        daoEffect { dao ->
            val id = model.id
                ?.toLongOrNull()
            val regex = model.regex.toString()
            if (id != null) {
                dao.update(
                    id = id,
                    name = model.name,
                    regex = regex,
                    command = model.command,
                    createdAt = model.createdDate,
                    enabled = model.enabled.int.toLong(),
                )
            } else {
                dao.insert(
                    name = model.name,
                    regex = regex,
                    command = model.command,
                    createdAt = model.createdDate,
                    enabled = model.enabled.int.toLong(),
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
        crossinline block: suspend (UrlOverrideQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.urlOverrideQueries
            block(dao)
        }
}
