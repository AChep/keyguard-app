package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRow
import com.artemchep.keyguard.common.service.sshagent.parseSshAgentPublicKeyMaterial
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.dataexposed.SshAgentPublicKey
import kotlinx.coroutines.CoroutineDispatcher
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SshAgentPublicKeyRepositoryImpl(
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val dispatcher: CoroutineDispatcher,
) : SshAgentPublicKeyRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        exposedDatabaseManager = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): IO<List<SshAgentPublicKeyRow>> = daoEffect {
        it.get()
            .executeAsList()
            .map(::parseEntity)
    }

    override fun getByPublicKeyBlobSha256(
        publicKeyBlobSha256: String,
    ): IO<List<SshAgentPublicKeyRow>> = daoEffect {
        it.getByPublicKeyBlobSha256(publicKeyBlobSha256)
            .executeAsList()
            .map(::parseEntity)
    }

    override fun getByPublicKey(
        publicKey: String,
    ): IO<List<SshAgentPublicKeyRow>> = ioEffect(dispatcher) {
        val material = parseSshAgentPublicKeyMaterial(
            publicKey = publicKey,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        ) ?: return@ioEffect emptyList()
        getByPublicKeyBlobSha256(material.publicKeyBlobSha256)
            .bind()
    }

    override fun replaceAll(
        keys: List<SshAgentPublicKeyRow>,
    ): IO<Unit> = exposedDatabaseManager.mutate("SshAgentPublicKeyRepository.replaceAll") { db ->
        db.sshAgentPublicKeyQueries.transaction {
            db.sshAgentPublicKeyQueries.deleteAll()
            keys
                .distinctBy { it.accountId to it.cipherId }
                .forEach { key ->
                    db.sshAgentPublicKeyQueries.insert(
                        accountId = key.accountId,
                        cipherId = key.cipherId,
                        publicKey = key.publicKey,
                        publicKeyBlobSha256 = key.publicKeyBlobSha256,
                        keyType = key.keyType,
                        fingerprint = key.fingerprint,
                        canSign = key.canSign,
                        name = key.name,
                    )
                }
        }
    }

    override fun clear(): IO<Unit> =
        exposedDatabaseManager.mutate("SshAgentPublicKeyRepository.clear") { db ->
            db.sshAgentPublicKeyQueries.deleteAll()
        }

    override fun clearNames(): IO<Unit> =
        exposedDatabaseManager.mutate("SshAgentPublicKeyRepository.clearNames") { db ->
            db.sshAgentPublicKeyQueries.clearNames()
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (com.artemchep.keyguard.dataexposed.SshAgentPublicKeyQueries) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val exposedDb = exposedDatabaseManager
            .get()
            .bind()
        block(exposedDb.sshAgentPublicKeyQueries)
    }

    private fun parseEntity(
        entity: SshAgentPublicKey,
    ) = SshAgentPublicKeyRow(
        accountId = entity.accountId,
        cipherId = entity.cipherId,
        publicKeyBlobSha256 = entity.publicKeyBlobSha256,
        publicKey = entity.publicKey,
        keyType = entity.keyType,
        fingerprint = entity.fingerprint,
        canSign = entity.canSign,
        name = entity.name,
    )
}
