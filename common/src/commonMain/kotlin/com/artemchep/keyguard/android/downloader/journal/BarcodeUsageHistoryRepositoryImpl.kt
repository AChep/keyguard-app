package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DBarcodeUsageHistory
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToOneOrNull
import com.artemchep.keyguard.data.BarcodeUsageHistory
import com.artemchep.keyguard.data.BarcodeUsageHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BarcodeUsageHistoryRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : BarcodeUsageHistoryRepository {
    companion object {
        private const val TAG = "BarcodeUsageHistoryRepository"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DBarcodeUsageHistory>> = getRecent()

    override fun getById(
        id: String,
    ): Flow<DBarcodeUsageHistory?> =
        daoEffect { dao ->
            dao.getById(id = id)
        }
            .flatMapQueryToOneOrNull(dispatcher)
            .map { entity ->
                entity?.toDomain()
            }

    override fun getRecent(
        limit: Long,
    ): Flow<List<DBarcodeUsageHistory>> =
        daoEffect { dao ->
            dao.getRecent(limit = limit)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(BarcodeUsageHistory::toDomain)
            }

    override fun put(model: DBarcodeUsageHistory): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.barcodeUsageHistoryQueries.upsert(
                id = model.id,
                type = model.type,
                createdAt = model.createdAt,
            )
            Unit
        }

    override fun removeAll(): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.barcodeUsageHistoryQueries.deleteAll()
            Unit
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (BarcodeUsageHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db.barcodeUsageHistoryQueries)
        }
}

private fun BarcodeUsageHistory.toDomain(): DBarcodeUsageHistory = DBarcodeUsageHistory(
    id = id,
    type = type,
    createdAt = createdAt,
)
