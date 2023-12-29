package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.worker.SyncWorker
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class QueueSyncAllAndroid(
    private val context: Context,
) : QueueSyncAll {
    constructor(directDI: DirectDI) : this(
        context = directDI.instance<Application>(),
    )

    override fun invoke(): IO<Unit> = ioEffect {
        SyncWorker.enqueueOnce(
            context = context,
        )
    }
}
