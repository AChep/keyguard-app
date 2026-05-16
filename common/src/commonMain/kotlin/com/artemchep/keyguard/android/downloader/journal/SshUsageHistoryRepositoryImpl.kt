package com.artemchep.keyguard.android.downloader.journal

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.data.SshUsageHistory
import com.artemchep.keyguard.data.SshUsageHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SshUsageHistoryRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : SshUsageHistoryRepository {
    companion object {
        private const val TAG = "SshUsageHistoryRepository"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DSshUsageHistory>> = getRecent()

    override fun getRecent(
        limit: Long,
    ): Flow<List<DSshUsageHistory>> =
        daoEffect { dao ->
            dao.getRecent(limit = limit)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(SshUsageHistory::toDomain)
            }

    override fun getByCipherId(
        cipherId: String,
        limit: Long,
    ): Flow<List<DSshUsageHistory>> =
        daoEffect { dao ->
            dao.getByCipherId(
                cipherId = cipherId,
                limit = limit,
            )
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(SshUsageHistory::toDomain)
            }

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

    override fun put(model: DSshUsageHistory): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.sshUsageHistoryQueries.insert(
                cipherId = model.cipherId,
                sessionId = model.sessionId,
                caller = model.caller,
                request = model.request,
                response = model.response,
                fingerprint = model.fingerprint,
                createdAt = model.instant,
            )
            Unit
        }

    override fun removeAll(): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.sshUsageHistoryQueries.deleteAll()
            Unit
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (SshUsageHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db.sshUsageHistoryQueries)
        }
}

private fun SshUsageHistory.toDomain(): DSshUsageHistory = DSshUsageHistory(
    cipherId = cipherId,
    sessionId = sessionId,
    caller = caller,
    request = request,
    response = response,
    fingerprint = fingerprint,
    instant = createdAt,
)
