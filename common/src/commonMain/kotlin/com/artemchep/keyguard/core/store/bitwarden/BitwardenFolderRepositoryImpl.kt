package com.artemchep.keyguard.core.store.bitwarden

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenFolderRepository
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

class BitwardenFolderRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : BitwardenFolderRepository {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<BitwardenFolder>> = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            db.folderQueries
                .get()
                .asFlow()
                .mapToList(dispatcher)
                .map {
                    it.map { it.data_ }
                }
        }
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000L), replay = 1)

    override fun put(model: BitwardenFolder): IO<Unit> = databaseManager
        .get()
        .map {
            TODO()
        }
}
