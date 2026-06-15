package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.PrivilegedAppQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock

class UserPrivilegedAppRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : UserPrivilegedAppRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DPrivilegedApp>> =
        daoEffect { dao ->
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
        daoEffect { dao ->
            val id = model.id
                ?.toLongOrNull()
            val createdAt = model.createdDate
                ?: Clock.System.now()
            if (id != null) {
                dao.update(
                    id = id,
                    name = model.name,
                    packageName = model.packageName,
                    certFingerprintSha256 = model.certFingerprintSha256,
                    createdAt = createdAt,
                )
            } else {
                dao.insert(
                    name = model.name,
                    packageName = model.packageName,
                    certFingerprintSha256 = model.certFingerprintSha256,
                    createdAt = createdAt,
                )
            }
        }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { dao ->
            dao.transaction {
                ids.forEach {
                    val id = it.toLongOrNull()
                        ?: return@forEach
                    dao.deleteByIds(id)
                }
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (PrivilegedAppQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.privilegedAppQueries
            block(dao)
        }
}
