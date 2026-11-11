package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.appsync.recordPut
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.PrivilegedAppQueries
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * The privileged apps the user added. Every write records the change for linked apps, in
 * the same transaction, see [AppSyncRecorder].
 */
class UserPrivilegedAppRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
    private val recorder: AppSyncRecorder = AppSyncRecorder.None,
) : UserPrivilegedAppRepository {
    override fun get(): Flow<List<DPrivilegedApp>> =
        daoEffect { _, dao ->
            dao.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        val source = DPrivilegedApp.Source.USER
                        DPrivilegedApp(
                            id = entity.id.toString(),
                            name = entity.name,
                            packageName = entity.packageName,
                            certFingerprintSha256 = entity.certFingerprintSha256,
                            createdDate = entity.createdAt,
                            source = source,
                        )
                    }
            }

    override fun put(model: DPrivilegedApp): IO<Unit> =
        daoEffect { db, dao ->
            val createdAt = model.createdDate
                ?: Clock.System.now()
            recorder.recordPut(
                database = db,
                kind = EntryKind.PRIVILEGED_APP,
                find = { model.id?.toLongOrNull()?.let { id -> dao.getById(id).executeAsOneOrNull() } },
                syncIdOf = { it.syncId },
                update = { existing ->
                    dao.update(
                        id = existing.id,
                        name = model.name,
                        packageName = model.packageName,
                        certFingerprintSha256 = model.certFingerprintSha256,
                        createdAt = createdAt,
                    )
                },
                insert = { syncId ->
                    dao.insert(
                        name = model.name,
                        packageName = model.packageName,
                        certFingerprintSha256 = model.certFingerprintSha256,
                        createdAt = createdAt,
                        syncId = syncId,
                    )
                },
            )
        }

    override fun removeAll(): IO<Unit> =
        daoEffect { db, dao ->
            recorder.record(db, EntryKind.PRIVILEGED_APP, ids = { dao.getSyncIds().executeAsList() }) {
                dao.deleteAll()
            }
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { db, dao ->
            val localIds = ids.mapNotNull { it.toLongOrNull() }
            val syncIds = { dao.getSyncIdsByIds(localIds).executeAsList() }
            recorder.record(db, EntryKind.PRIVILEGED_APP, ids = syncIds) {
                dao.deleteByIds(localIds)
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (Database, PrivilegedAppQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            block(db, db.privilegedAppQueries)
        }
}
