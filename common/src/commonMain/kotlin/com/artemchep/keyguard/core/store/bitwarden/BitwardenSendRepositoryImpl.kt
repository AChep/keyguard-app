package com.artemchep.keyguard.core.store.bitwarden

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenSendRepository
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

class BitwardenSendRepositoryImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : BitwardenSendRepository {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<BitwardenSend>> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.sendQueries
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

    override fun put(model: BitwardenSend): IO<Unit> = databaseManager
        .get()
        .effectMap(dispatcher) {
            it.sendQueries.insert(
                accountId = model.accountId,
                sendId = model.sendId,
                data = model,
            )
            Unit
        }
}
