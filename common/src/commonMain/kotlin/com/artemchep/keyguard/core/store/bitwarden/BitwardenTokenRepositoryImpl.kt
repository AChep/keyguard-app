package com.artemchep.keyguard.core.store.bitwarden

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BitwardenTokenRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : BitwardenTokenRepository {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<BitwardenToken>> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.accountQueries
                .get()
                .asFlow()
                .mapToList(dispatcher)
                .map {
                    it.mapNotNull { it.data_ as? BitwardenToken }
                }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000L), replay = 1)

    override fun getById(id: AccountId): IO<BitwardenToken?> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val infoEntity = db.accountQueries
                .getByAccountId(accountId = id.id)
                .executeAsOneOrNull()
            infoEntity?.data_ as? BitwardenToken?
        }

    override fun put(model: BitwardenToken): IO<Unit> = databaseManager
        .get()
        .effectMap(dispatcher) {
            it.accountQueries.insert(
                accountId = model.id,
                data = model,
            )
        }
}

class ServiceTokenRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : ServiceTokenRepository {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<ServiceToken>> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.accountQueries
                .get()
                .asFlow()
                .mapToList(dispatcher)
                .map {
                    it.map { it.data_ }
                }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000L), replay = 1)

    override fun getById(id: AccountId): IO<ServiceToken?> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val infoEntity = db.accountQueries
                .getByAccountId(accountId = id.id)
                .executeAsOneOrNull()
            infoEntity?.data_
        }

    override fun put(model: ServiceToken): IO<Unit> = databaseManager
        .get()
        .effectMap(dispatcher) {
            it.accountQueries.insert(
                accountId = model.id,
                data = model,
            )
        }
}
