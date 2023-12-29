package com.artemchep.keyguard.android.downloader.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import kotlinx.coroutines.flow.first
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import java.util.concurrent.TimeUnit

class AttachmentDownloadAllWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), DIAware {
    companion object {
        private const val WORK_ID = "AttachmentDownloadAllWorker"

        fun enqueue(
            context: Context,
        ): Operation {
            val request = PeriodicWorkRequestBuilder<AttachmentDownloadAllWorker>(
                repeatInterval = 8,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            return WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_ID, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    override val di by closestDI { applicationContext }

    override suspend fun doWork(): Result {
        val downloadRepository: DownloadRepository by instance()
        // Check what downloads we currently have and
        // start the unfinished ones again.
        val downloadList = downloadRepository.get().first()
        downloadList.forEach { downloadInfo ->
            val context = applicationContext
            val args = AttachmentDownloadWorker.Args(
                downloadId = downloadInfo.id,
            )
            AttachmentDownloadWorker.enqueueOnce(
                context = context,
                args = args,
            )
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo() = TODO()
}
