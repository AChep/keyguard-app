package com.artemchep.keyguard.android.downloader

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.downloader.worker.ExportWorker
import com.artemchep.keyguard.common.service.export.impl.ExportManagerBase
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ExportManagerImpl(
    private val directDI: DirectDI,
    private val context: Context,
) : ExportManagerBase(
    directDI = directDI,
    onLaunch = { id ->
        val args = ExportWorker.Args(
            exportId = id,
        )
        ExportWorker.enqueueOnce(
            context = context,
            args = args,
        )
    }
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        directDI = directDI,
        context = directDI.instance<Application>(),
    )
}
