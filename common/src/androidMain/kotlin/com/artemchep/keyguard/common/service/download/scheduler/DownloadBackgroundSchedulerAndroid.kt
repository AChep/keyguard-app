package com.artemchep.keyguard.common.service.download.scheduler

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.downloader.worker.AttachmentDownloadWorker
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadBackgroundSchedulerAndroid(
    private val context: Context,
) : DownloadBackgroundScheduler {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override suspend fun enqueue(downloadId: String) {
        val args = AttachmentDownloadWorker.Args(
            downloadId = downloadId,
        )
        AttachmentDownloadWorker.enqueueOnce(
            context = context,
            args = args,
        )
    }
}
