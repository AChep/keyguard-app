package com.artemchep.keyguard.core.store.bitwarden

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
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

class BitwardenProfileRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : BitwardenProfileRepository {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<BitwardenProfile>> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.profileQueries
                .get()
                .asFlow()
                .mapToList(dispatcher)
                .map {
                    it.map { it.data_ }
                }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000L), replay = 1)

    override fun getById(id: AccountId): Flow<BitwardenProfile?> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.profileQueries
                .getByAccountId(accountId = id.id)
                .asFlow()
                .mapToOneOrNull(dispatcher)
                .map {
                    it?.data_
                }
        }

    override fun put(model: BitwardenProfile): IO<Unit> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            db.profileQueries.insert(
                model,
                accountId = model.accountId,
                profileId = model.profileId,
            )
        }
}
