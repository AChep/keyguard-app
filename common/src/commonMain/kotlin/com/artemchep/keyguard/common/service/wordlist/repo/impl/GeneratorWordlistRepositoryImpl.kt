package com.artemchep.keyguard.common.service.wordlist.repo.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.GeneratorWordlistQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GeneratorWordlistRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GeneratorWordlistRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGeneratorWordlist>> =
        daoEffect { dao ->
            dao.get()
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        DGeneratorWordlist(
                            idRaw = entity.id,
                            name = entity.name,
                            wordCount = entity.wordCount,
                            createdDate = entity.createdAt,
                        )
                    }
            }

    override fun post(
        name: String,
        wordlist: List<String>,
    ): IO<Unit> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val now = Clock.System.now()
            db.transaction {
                db.generatorWordlistQueries.insert(
                    name = name,
                    createdAt = now,
                )
                val wordlistId = db.utilQueries
                    .getLastInsertRowId()
                    .executeAsOne()
                wordlist.forEach { word ->
                    db.generatorWordlistWordQueries.insert(
                        wordlistId = wordlistId,
                        word = word,
                    )
                }
            }
        }

    override fun patch(
        id: Long,
        name: String,
    ): IO<Unit> = daoEffect { dao ->
        dao.update(
            id = id,
            name = name,
        )
    }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    override fun removeByIds(ids: Set<Long>): IO<Unit> =
        daoEffect { dao ->
            dao.transaction {
                ids.forEach { id ->
                    dao.deleteByIds(id)
                }
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (GeneratorWordlistQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.generatorWordlistQueries
            block(dao)
        }
}
