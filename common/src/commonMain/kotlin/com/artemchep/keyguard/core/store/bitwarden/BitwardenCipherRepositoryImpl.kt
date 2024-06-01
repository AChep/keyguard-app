package com.artemchep.keyguard.core.store.bitwarden

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BitwardenCipherRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : BitwardenCipherRepository {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<BitwardenCipher>> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.cipherQueries
                .get()
                .asFlow()
                .mapToList(dispatcher)
                .catch {
                }
                .map {
                    it.map { it.data_ }
                }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000L), replay = 1)

    override fun getById(id: String): IO<BitwardenCipher?> = get()
        .toIO()
        .effectMap { ciphers ->
            ciphers.firstOrNull { it.cipherId == id }
        }

    override fun put(model: BitwardenCipher): IO<Unit> = databaseManager
        .get()
        .effectMap(dispatcher) {
            it.cipherQueries.insert(
                accountId = model.accountId,
                cipherId = model.cipherId,
                folderId = null,
                data = model,
                updatedAt = model.revisionDate,
            )
        }
}
