package com.artemchep.keyguard.copy

import android.content.Context
import com.artemchep.keyguard.android.worker.SyncWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.QueueSyncById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class QueueSyncByIdAndroid(
    private val context: Context,
) : QueueSyncById {
    constructor(directDI: DirectDI) : this(
        context = directDI.instance(),
    )

    override fun invoke(
        accountId: AccountId,
    ): IO<Unit> = ioEffect {
        SyncWorker.enqueueOnce(
            context = context,
            accounts = setOf(
                accountId,
            ),
        )
    }
}
