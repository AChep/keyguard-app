package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.SyncById
import kotlinx.coroutines.GlobalScope

/**
 * @author Artem Chepurnyi
 */
class QueueSyncByIdImpl(
    private val syncById: SyncById,
) : QueueSyncById {
    companion object {
        private const val TAG = "QueueSyncById.bitwarden"
    }

    override fun invoke(accountId: AccountId): IO<Unit> = ioEffect {
        syncById(accountId)
            .attempt()
            .launchIn(GlobalScope)
    }
}
