package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.GeneratorHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GeneratorHistoryRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GeneratorHistoryRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGeneratorHistory>> =
        daoEffect { dao ->
            dao.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        DGeneratorHistory(
                            id = entity.id.toString(),
                            value = entity.value_,
                            createdDate = entity.createdAt,
                            isPassword = entity.isPassword,
                            isUsername = entity.isUsername,
                            isEmailRelay = entity.isEmailRelay == true,
                        )
                    }
            }

    override fun put(model: DGeneratorHistory): IO<Unit> =
        daoEffect { dao ->
            dao.insert(
                value_ = model.value,
                createdAt = model.createdDate,
                isPassword = model.isPassword,
                isUsername = model.isUsername,
                isEmailRelay = model.isEmailRelay,
            )
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
        crossinline block: suspend (GeneratorHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.generatorHistoryQueries
            block(dao)
        }
}
