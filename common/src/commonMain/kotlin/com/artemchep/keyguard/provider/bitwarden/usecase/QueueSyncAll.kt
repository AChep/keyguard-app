package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.SyncAll
import kotlinx.coroutines.GlobalScope

/**
 * @author Artem Chepurnyi
 */
class QueueSyncAllImpl(
    private val syncAll: SyncAll,
) : QueueSyncAll {
    companion object {
        private const val TAG = "QueueSyncById.bitwarden"
    }

    override fun invoke(): IO<Unit> = ioEffect {
        syncAll()
            .attempt()
            .launchIn(GlobalScope)
    }
}
