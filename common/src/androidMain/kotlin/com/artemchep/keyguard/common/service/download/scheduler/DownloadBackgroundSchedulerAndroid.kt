package com.artemchep.keyguard.common.service.download.scheduler

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.downloader.worker.AttachmentDownloadWorker

class DownloadBackgroundSchedulerAndroid(
    private val context: Context,
) : DownloadBackgroundScheduler {

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
