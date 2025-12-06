package com.artemchep.keyguard.android.downloader.journal

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DCipherOpenedHistory
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.CipherUsageHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CipherHistoryOpenedRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : CipherHistoryOpenedRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun getCount(): Flow<Long> =
        daoEffect { dao ->
            dao.getCount()
        }
            .asFlow()
            .flatMapLatest { query ->
                query
                    .asFlow()
                    .mapToOne(dispatcher)
            }

    override fun get(): Flow<List<DCipherOpenedHistory>> = getRecent()

    override fun getRecent(): Flow<List<DCipherOpenedHistory>> =
        daoEffect { dao ->
            dao.getDistinctRecent(100)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .mapNotNull { entity ->
                        val instant = entity.createdAt
                        // should never happen
                            ?: return@mapNotNull null
                        DCipherOpenedHistory(
                            cipherId = entity.cipherId,
                            instant = instant,
                        )
                    }
            }

    override fun getPopular(): Flow<List<DCipherOpenedHistory>> =
        daoEffect { dao ->
            dao.getDistinctPopular(100)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        DCipherOpenedHistory(
                            cipherId = entity.cipherId,
                            instant = entity.createdAt,
                        )
                    }
            }

    override fun getCredentialLastUsed(
        cipherId: String,
        credentialId: String,
    ): Flow<Instant?> =
        daoEffect { dao ->
            dao.getUsedPasskeyById(
                cipherId = cipherId,
                credentialId = credentialId,
                limit = 1L,
            )
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                val entity = entities.firstOrNull()
                entity?.createdAt
            }

    override fun put(model: DCipherOpenedHistory): IO<Unit> =
        daoEffect { dao ->
            TODO()
        }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (CipherUsageHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.cipherUsageHistoryQueries
            block(dao)
        }
}
