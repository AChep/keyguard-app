package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToOne
import com.artemchep.keyguard.data.GpgUsageHistory
import com.artemchep.keyguard.data.GpgUsageHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgUsageHistoryRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GpgUsageHistoryRepository {
    companion object {
        private const val TAG = "GpgUsageHistoryRepository"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGpgUsageHistory>> = getRecent()

    override fun getRecent(
        limit: Long,
    ): Flow<List<DGpgUsageHistory>> =
        daoEffect { dao ->
            dao.getRecent(limit = limit)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(GpgUsageHistory::toDomain)
            }

    override fun getByCipherId(
        cipherId: String,
        limit: Long,
    ): Flow<List<DGpgUsageHistory>> =
        daoEffect { dao ->
            dao.getByCipherId(
                cipherId = cipherId,
                limit = limit,
            )
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(GpgUsageHistory::toDomain)
            }

    override fun getCount(): Flow<Long> =
        daoEffect { dao ->
            dao.getCount()
        }
            .flatMapQueryToOne(dispatcher)

    override fun put(model: DGpgUsageHistory): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.gpgUsageHistoryQueries.insert(
                cipherId = model.cipherId,
                sessionId = model.sessionId,
                caller = model.caller,
                request = model.request,
                response = model.response,
                fingerprint = model.fingerprint,
                keygrip = model.keygrip,
                createdAt = model.instant,
                eventId = model.eventId,
            )
            Unit
        }

    override fun removeAll(): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.gpgUsageHistoryQueries.deleteAll()
            Unit
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (GpgUsageHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db.gpgUsageHistoryQueries)
        }
}

private fun GpgUsageHistory.toDomain(): DGpgUsageHistory = DGpgUsageHistory(
    id = id.toString(),
    cipherId = cipherId,
    sessionId = sessionId,
    caller = caller,
    request = request,
    response = response,
    fingerprint = fingerprint,
    keygrip = keygrip,
    instant = createdAt,
    eventId = eventId,
)
