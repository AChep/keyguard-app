package com.artemchep.keyguard.common.service.download.scheduler

fun interface DownloadBackgroundScheduler {
    suspend fun enqueue(downloadId: String)
}
