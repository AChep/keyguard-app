package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.premium
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.Database
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ModifyDatabase(
    private val db: DatabaseManager,
    private val getCanWrite: GetCanWrite,
    private val getWriteAccess: GetWriteAccess,
    private val queueSyncById: QueueSyncById,
) {
    companion object {
        private const val TAG = "ModifyDatabase.bitwarden"
    }

    data class Result<T>(
        val changedAccountIds: Set<AccountId>,
        val value: T,
    ) {
        companion object {
            fun unit(
                changedAccountIds: Set<AccountId> = emptySet(),
            ) = Result(
                changedAccountIds = changedAccountIds,
                value = Unit,
            )
        }
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
        getCanWrite = directDI.instance(),
        getWriteAccess = directDI.instance(),
        queueSyncById = directDI.instance(),
    )

    operator fun <T> invoke(
        block: suspend (Database) -> Result<T>,
    ): IO<T> = db
        .mutate("ModifyDatabase") { database ->
            val accountIds = block(database)
            accountIds
        }
        .effectTap { result ->
            // TODO: FIX ME
            result.changedAccountIds.forEach {
                queueSyncById(it)
                    .launchIn(GlobalScope)
            }
            // val accountIds = result.changedAccountIds
            // SyncWorker.enqueueOnce(context, accountIds)
        }
        .map { it.value }
        // Require premium for modifying a cipher.
        .premium(
            getPurchased = {
                combine(
                    getCanWrite(),
                    getWriteAccess(),
                ) { a, b -> a && b }
            },
        )
}
