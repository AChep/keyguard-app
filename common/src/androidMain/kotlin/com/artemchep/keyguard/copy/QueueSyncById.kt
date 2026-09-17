package com.artemchep.keyguard.copy

import android.content.Context
import com.artemchep.keyguard.android.worker.SyncWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository

/**
 * @author Artem Chepurnyi
 */
class QueueSyncByIdAndroid(
    private val context: Context,
    private val tokenRepository: ServiceTokenRepository,
) : QueueSyncById {

    override fun invoke(
        accountId: AccountId,
    ): IO<Unit> = ioEffect {
        val account = tokenRepository.getById(accountId).bind()
            ?: return@ioEffect
        SyncWorker.enqueueOnce(
            context = context,
            accounts = listOf(account),
        )
    }
}
