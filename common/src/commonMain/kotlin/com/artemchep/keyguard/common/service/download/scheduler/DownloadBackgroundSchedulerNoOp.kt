package com.artemchep.keyguard.common.service.download.scheduler

object DownloadBackgroundSchedulerNoOp : DownloadBackgroundScheduler {
    override suspend fun enqueue(downloadId: String) = Unit
}
