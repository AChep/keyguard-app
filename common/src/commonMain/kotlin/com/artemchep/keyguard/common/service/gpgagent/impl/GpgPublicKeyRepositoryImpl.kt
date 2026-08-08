package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyInfoRow
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgKeygrip
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import kotlinx.coroutines.CoroutineDispatcher
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgPublicKeyRepositoryImpl(
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : GpgPublicKeyRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        exposedDatabaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun getPublicKeys(): IO<List<GpgPublicKeyRow>> = daoEffect { db ->
        db.gpgPublicKeyQueries
            .get()
            .executeAsList()
            .mapNotNull { entity ->
                GpgPublicKeyRow(
                    accountId = entity.accountId,
                    cipherId = entity.cipherId,
                    publicKeyArmored = entity.publicKeyArmored
                        ?: return@mapNotNull null,
                    primaryFingerprint = entity.primaryFingerprint
                        ?: return@mapNotNull null,
                    canSign = entity.canSign,
                    canDecrypt = entity.canDecrypt,
                    name = entity.name,
                )
            }
    }

    override fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>> = daoEffect { db ->
        db.gpgAgentKeyInfoQueries
            .get(::GpgAgentKeyInfoRow)
            .executeAsList()
    }

    override fun getKeyInfoByKeygrip(
        keygrip: String,
    ): IO<List<GpgAgentKeyInfoRow>> = daoEffect { db ->
        db.gpgAgentKeyInfoQueries
            .getByKeygrip(keygrip.normalizeGpgKeygrip(), ::GpgAgentKeyInfoRow)
            .executeAsList()
    }

    override fun replaceAll(
        entries: List<GpgPublicKeyEntry>,
    ): IO<Unit> = exposedDatabaseManager.mutate("GpgPublicKeyRepository.replaceAll") { db ->
        db.gpgPublicKeyQueries.transaction {
            db.gpgAgentKeyInfoQueries.deleteAll()
            db.gpgPublicKeyQueries.deleteAll()
            entries
                .distinctBy { it.accountId to it.cipherId }
                .forEach { entry ->
                    db.gpgPublicKeyQueries.insert(
                        accountId = entry.accountId,
                        cipherId = entry.cipherId,
                        publicKeyArmored = entry.publicKeyArmored,
                        primaryFingerprint = entry.primaryFingerprint,
                        canSign = entry.canSign,
                        canDecrypt = entry.canDecrypt,
                        name = entry.name,
                    )
                    entry.keyInfo
                        .filter { it.keygrip.isNotBlank() }
                        .distinctBy { it.keygrip.normalizeGpgKeygrip() }
                        .forEach { key ->
                            db.gpgAgentKeyInfoQueries.insert(
                                accountId = entry.accountId,
                                cipherId = entry.cipherId,
                                keygrip = key.keygrip.normalizeGpgKeygrip(),
                                fingerprint = key.fingerprint,
                                algorithm = key.algorithm,
                                canSign = key.canSign,
                                canDecrypt = key.canDecrypt,
                            )
                        }
                }
        }
    }

    override fun clear(): IO<Unit> =
        exposedDatabaseManager.mutate("GpgPublicKeyRepository.clear") { db ->
            db.gpgPublicKeyQueries.transaction {
                db.gpgAgentKeyInfoQueries.deleteAll()
                db.gpgPublicKeyQueries.deleteAll()
            }
        }

    override fun clearNames(): IO<Unit> =
        exposedDatabaseManager.mutate("GpgPublicKeyRepository.clearNames") { db ->
            db.gpgPublicKeyQueries.clearNames()
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (DatabaseExposed) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val exposedDb = exposedDatabaseManager
            .get()
            .bind()
        block(exposedDb)
    }
}
