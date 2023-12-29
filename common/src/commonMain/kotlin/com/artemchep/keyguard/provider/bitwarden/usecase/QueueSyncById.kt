package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.SyncById
import kotlinx.coroutines.GlobalScope
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class QueueSyncByIdImpl(
    private val syncById: SyncById,
) : QueueSyncById {
    companion object {
        private const val TAG = "QueueSyncById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        syncById = directDI.instance(),
    )

    override fun invoke(accountId: AccountId): IO<Unit> = ioEffect {
        syncById(accountId)
            .attempt()
            .launchIn(GlobalScope)
    }
}
