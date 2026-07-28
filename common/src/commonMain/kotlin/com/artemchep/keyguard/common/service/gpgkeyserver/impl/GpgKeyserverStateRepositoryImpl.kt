package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToOneOrNull
import com.artemchep.keyguard.data.GpgKeyserverState
import com.artemchep.keyguard.data.GpgKeyserverStateQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgKeyserverStateRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GpgKeyserverStateRepository {
    companion object {
        private const val TAG = "GpgKeyserverStateRepository"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun getAll(): Flow<List<DGpgKeyserverState>> =
        daoEffect { dao ->
            dao.getAll()
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(GpgKeyserverState::toDomain)
            }

    override fun getByFingerprint(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?> =
        daoEffect { dao ->
            dao.getByFingerprint(fingerprint.normalizeGpgFingerprint())
        }
            .flatMapQueryToOneOrNull(dispatcher)
            .map { it?.toDomain() }

    override fun getByCipherId(
        cipherId: String,
    ): Flow<List<DGpgKeyserverState>> =
        daoEffect { dao ->
            dao.getByCipherId(cipherId)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities.map(GpgKeyserverState::toDomain)
            }

    override fun put(
        model: DGpgKeyserverState,
    ): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.gpgKeyserverStateQueries.insertOrReplace(
                fingerprint = model.fingerprint.normalizeGpgFingerprint(),
                cipherId = model.cipherId,
                verificationStatus = model.verificationStatus,
                lastCheckedAt = model.lastCheckedAt,
                lastRefreshedAt = model.lastRefreshedAt,
                sourceKeyserver = model.sourceKeyserver,
            )
            Unit
        }

    override fun removeByFingerprint(
        fingerprint: String,
    ): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.gpgKeyserverStateQueries.deleteByFingerprint(fingerprint.normalizeGpgFingerprint())
            Unit
        }

    override fun removeAll(): IO<Unit> =
        databaseManager.mutate(TAG) { db ->
            db.gpgKeyserverStateQueries.deleteAll()
            Unit
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (GpgKeyserverStateQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db.gpgKeyserverStateQueries)
        }
}

private fun GpgKeyserverState.toDomain(): DGpgKeyserverState = DGpgKeyserverState(
    fingerprint = fingerprint,
    cipherId = cipherId,
    verificationStatus = verificationStatus,
    lastCheckedAt = lastCheckedAt,
    lastRefreshedAt = lastRefreshedAt,
    sourceKeyserver = sourceKeyserver,
)
