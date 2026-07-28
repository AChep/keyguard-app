package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgKeygrip
import com.artemchep.keyguard.dataexposed.GpgAgentPublicKey
import kotlinx.coroutines.CoroutineDispatcher
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgAgentPublicKeyRepositoryImpl(
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GpgAgentPublicKeyRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        exposedDatabaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): IO<List<GpgAgentPublicKeyRow>> = daoEffect {
        it.get()
            .executeAsList()
            .map(::parseEntity)
    }

    override fun getByKeygrip(
        keygrip: String,
    ): IO<GpgAgentPublicKeyRow?> = daoEffect {
        it.getByKeygrip(keygrip.normalizeGpgKeygrip())
            .executeAsOneOrNull()
            ?.let(::parseEntity)
    }

    override fun replaceAll(
        keys: List<GpgAgentPublicKeyRow>,
    ): IO<Unit> = exposedDatabaseManager.mutate("GpgAgentPublicKeyRepository.replaceAll") { db ->
        db.gpgAgentPublicKeyQueries.transaction {
            db.gpgAgentPublicKeyQueries.deleteAll()
            keys
                .filter { it.keygrip.isNotBlank() }
                .distinctBy { it.keygrip.normalizeGpgKeygrip() }
                .sortedWith(
                    compareBy<GpgAgentPublicKeyRow> { it.fingerprint }
                        .thenBy { it.keygrip },
                )
                .forEach { key ->
                    db.gpgAgentPublicKeyQueries.insert(
                        keygrip = key.keygrip.normalizeGpgKeygrip(),
                        fingerprint = key.fingerprint,
                        algorithm = key.algorithm,
                        canSign = key.canSign,
                        canDecrypt = key.canDecrypt,
                        publicKeyArmored = key.publicKeyArmored,
                        name = key.name,
                    )
                }
        }
    }

    override fun clear(): IO<Unit> =
        exposedDatabaseManager.mutate("GpgAgentPublicKeyRepository.clear") { db ->
            db.gpgAgentPublicKeyQueries.deleteAll()
        }

    override fun clearNames(): IO<Unit> =
        exposedDatabaseManager.mutate("GpgAgentPublicKeyRepository.clearNames") { db ->
            db.gpgAgentPublicKeyQueries.clearNames()
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (com.artemchep.keyguard.dataexposed.GpgAgentPublicKeyQueries) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val exposedDb = exposedDatabaseManager
            .get()
            .bind()
        block(exposedDb.gpgAgentPublicKeyQueries)
    }

    private fun parseEntity(
        entity: GpgAgentPublicKey,
    ) = GpgAgentPublicKeyRow(
        keygrip = entity.keygrip,
        fingerprint = entity.fingerprint,
        algorithm = entity.algorithm,
        canSign = entity.canSign,
        canDecrypt = entity.canDecrypt,
        publicKeyArmored = entity.publicKeyArmored,
        name = entity.name,
    )
}
