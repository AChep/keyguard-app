package com.artemchep.keyguard.common.service.wordlist.repo.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorWord
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistWordRepository
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.GeneratorWordlistWordQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GeneratorWordlistWordRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GeneratorWordlistWordRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGeneratorWord>> =
        daoEffect { dao ->
            dao.get()
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        DGeneratorWord(
                            id = entity.id.toString(),
                            word = entity.word,
                        )
                    }
            }

    override fun getWords(
        wordlistId: Long,
    ): Flow<List<String>> =
        daoEffect { dao ->
            dao.getPrimitiveByWordlistId(wordlistId)
        }
            .flatMapQueryToList(dispatcher)

    override fun put(model: DGeneratorWord): IO<Unit> =
        daoEffect { dao ->
            TODO()
        }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { dao ->
            TODO()
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (GeneratorWordlistWordQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.generatorWordlistWordQueries
            block(dao)
        }
}
