package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.worker.SyncWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository

/**
 * @author Artem Chepurnyi
 */
class QueueSyncAllAndroid(
    private val context: Context,
    private val tokenRepository: ServiceTokenRepository,
) : QueueSyncAll {

    override fun invoke(): IO<Unit> = ioEffect {
        val accounts = tokenRepository.getSnapshot().bind()
        SyncWorker.enqueueOnce(
            context = context,
            accounts = accounts,
        )
    }
}
