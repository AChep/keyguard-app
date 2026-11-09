package com.artemchep.keyguard.common.service.wordlist.repo.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The wordlists. Every write records the change for linked apps, in the same
 * transaction, see [AppSyncRecorder].
 */
class GeneratorWordlistRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
    private val recorder: AppSyncRecorder = AppSyncRecorder.None,
) : GeneratorWordlistRepository {
    override fun get(): Flow<List<DGeneratorWordlist>> =
        databaseEffect { db ->
            db.generatorWordlistQueries.get()
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
    ): IO<Unit> = databaseEffect { db ->
        val now = Clock.System.now()
        val syncId = Uuid.random().toString()
        recorder.record(db, EntryKind.WORDLIST, ids = { listOf(syncId) }) {
            db.generatorWordlistQueries.insert(
                name = name,
                createdAt = now,
                syncId = syncId,
                wordsHash = null,
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
    ): IO<Unit> = databaseEffect { db ->
        val dao = db.generatorWordlistQueries
        recorder.record(db, EntryKind.WORDLIST, ids = { dao.getSyncIdsByIds(listOf(id)).executeAsList() }) {
            dao.update(
                id = id,
                name = name,
            )
        }
    }

    override fun removeAll(): IO<Unit> =
        databaseEffect { db ->
            val dao = db.generatorWordlistQueries
            recorder.record(db, EntryKind.WORDLIST, ids = { dao.getSyncIds().executeAsList() }) {
                dao.deleteAll()
            }
        }

    override fun removeByIds(ids: Set<Long>): IO<Unit> =
        databaseEffect { db ->
            val dao = db.generatorWordlistQueries
            recorder.record(db, EntryKind.WORDLIST, ids = { dao.getSyncIdsByIds(ids).executeAsList() }) {
                dao.deleteByIds(ids)
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
