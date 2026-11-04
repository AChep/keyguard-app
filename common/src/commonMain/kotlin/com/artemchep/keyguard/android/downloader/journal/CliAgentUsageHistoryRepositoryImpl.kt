package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToOne
import com.artemchep.keyguard.data.CliAgentUsageHistory
import com.artemchep.keyguard.data.CliAgentUsageHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

// Every client shares one table, so a write re-runs the queries of
// the other clients too; their unchanged results are not emitted.
class CliAgentUsageHistoryRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : CliAgentUsageHistoryRepository {
    companion object {
        private const val TAG = "CliAgentUsageHistoryRepository"
    }

    override fun getRecent(
        client: CliAgentUsageHistoryClient,
        limit: Long,
    ): Flow<List<DCliAgentUsageHistory>> =
        daoEffect { dao ->
            dao.getRecentByClient(
                client = client,
                limit = limit,
            )
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(CliAgentUsageHistory::toDomain)
            }
            .distinctUntilChanged()

    override fun getByCipherId(
        cipherId: String,
        limit: Long,
    ): Flow<List<DCliAgentUsageHistory>> =
        daoEffect { dao ->
            dao.getByCipherId(
                cipherId = cipherId,
                limit = limit,
            )
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(CliAgentUsageHistory::toDomain)
            }
            .distinctUntilChanged()

    override fun getCount(
        client: CliAgentUsageHistoryClient,
    ): Flow<Long> =
        daoEffect { dao ->
            dao.getCountByClient(client = client)
        }
            .flatMapQueryToOne(dispatcher)
            .distinctUntilChanged()

    override fun put(model: DCliAgentUsageHistory): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.cliAgentUsageHistoryQueries.insert(
                cipherId = model.cipherId,
                sessionId = model.sessionId,
                client = model.client,
                caller = model.caller,
                clientName = model.clientName,
                request = model.request,
                response = model.response,
                errorCode = model.errorCode,
                command = model.command,
                path = model.path,
                createdAt = model.instant,
                eventId = model.eventId,
            )
        }

    override fun removeByClient(
        client: CliAgentUsageHistoryClient,
    ): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.cliAgentUsageHistoryQueries.deleteByClient(client = client)
        }

    override fun removeOlderThan(
        cutoff: Instant,
    ): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.cliAgentUsageHistoryQueries.deleteOlderThan(cutoff = cutoff)
            Unit
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (CliAgentUsageHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db.cliAgentUsageHistoryQueries)
        }
}

private fun CliAgentUsageHistory.toDomain(): DCliAgentUsageHistory = DCliAgentUsageHistory(
    id = id.toString(),
    cipherId = cipherId,
    sessionId = sessionId,
    client = client,
    caller = caller,
    clientName = clientName,
    request = request,
    response = response,
    errorCode = errorCode,
    command = command,
    path = path,
    instant = createdAt,
    eventId = eventId,
)
