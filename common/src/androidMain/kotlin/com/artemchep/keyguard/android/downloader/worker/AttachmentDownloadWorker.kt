package com.artemchep.keyguard.android.downloader.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.receiver.AttachmentDownloadActionReceiver
import com.artemchep.keyguard.android.downloader.withId
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.ui.canRetry
import com.artemchep.keyguard.ui.getHttpCode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AttachmentDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), DIAware {
    companion object {
        private const val WORK_ID = "AttachmentDownloadWorker"

        private const val PROGRESS_MAX = 100

        fun enqueueOnce(
            context: Context,
            args: Args,
        ): Operation {
            val data = Data.Builder()
                .apply {
                    args.populate(this)
                }
                .build()
            val request = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            val workId = buildWorkKey(args.downloadId)
            return WorkManager
                .getInstance(context)
                .enqueueUniqueWork(workId, ExistingWorkPolicy.KEEP, request)
        }

        private fun buildWorkKey(id: String) = "$WORK_ID:$id"
    }

    data class Args(
        val downloadId: String,
    ) {
        companion object {
            private const val KEY_DOWNLOAD_ID = "download_id"

            fun of(data: Data) = Args(
                downloadId = data.getString(KEY_DOWNLOAD_ID)!!,
            )
        }

        fun populate(builder: Data.Builder) {
            builder.putString(KEY_DOWNLOAD_ID, downloadId)
        }
    }

    override val di by closestDI { applicationContext }

    private val notificationManager = context.getSystemService<NotificationManager>()!!

    private var notificationId = Notifications.downloads.obtainId()

    override suspend fun doWork(): Result = run {
        val args = Args.of(inputData)
        internalDoWork(
            notificationId = notificationId,
            args = args,
        )
    }

    private suspend fun internalDoWork(
        notificationId: Int,
        args: Args,
    ): Result {
        val downloadManager: DownloadManager by instance()
        val downloadRepository: DownloadRepository by instance()

        val downloadInfo = downloadRepository.getById(id = args.downloadId)
            .bind()
        // Failed to start the downloading. This happens mostly if you cancel the
        // downloading immediately after clicking download.
            ?: return Result.failure()
        // Check if we are allowed to start downloading.

        val downloadStatusFlow = kotlin.run {
            val downloadIsOneTime = downloadInfo.urlIsOneTime
            val downloadFailed = downloadInfo.error != null && !downloadInfo.error.canRetry()
            if (downloadIsOneTime || downloadFailed) {
                // We can not restart the download, but we
                // can check if it's already there.
                val downloadStatusFlow = downloadManager
                    .statusByDownloadId2(downloadId = downloadInfo.id)
                // ...check if the status is other then None.
                val result = downloadStatusFlow
                    .filter { it !is DownloadProgress.None }
                    .toIO()
                    .timeout(500L)
                    .attempt()
                    .bind()
                if (result.isLeft()) {
                    return Result.failure()
                }
                downloadStatusFlow
            } else {
                // Start downloading.
                downloadManager
                    .queue(downloadInfo = downloadInfo)
                    .flow
            }
        }

        val result = downloadStatusFlow
            .onStart {
                val foregroundInfo = createForegroundInfo(
                    id = notificationId,
                    downloadId = downloadInfo.id,
                    name = downloadInfo.name,
                    progress = null,
                )
                setForeground(foregroundInfo)
            }
            .onEach { progress ->
                when (progress) {
                    is DownloadProgress.None -> {
                        val foregroundInfo = createForegroundInfo(
                            id = notificationId,
                            downloadId = downloadInfo.id,
                            name = downloadInfo.name,
                            progress = null,
                        )
                        setForeground(foregroundInfo)
                    }

                    is DownloadProgress.Loading -> {
                        val downloadedFormatted = progress.downloaded
                            ?.let(::humanReadableByteCountSI)
                        val totalFormatted = progress.total
                            ?.let(::humanReadableByteCountSI)

                        val p = progress.percentage
                        val foregroundInfo = createForegroundInfo(
                            id = notificationId,
                            downloadId = downloadInfo.id,
                            name = downloadInfo.name,
                            progress = p,
                            downloaded = downloadedFormatted,
                            total = totalFormatted,
                        )
                        setForeground(foregroundInfo)
                    }

                    is DownloadProgress.Complete -> {
                        // Do nothing
                        return@onEach
                    }
                }
            }
            // complete once we finish the download
            .transformWhile { progress ->
                emit(progress) // always emit progress
                progress !is DownloadProgress.Complete
            }
            .last()
        require(result is DownloadProgress.Complete)
        return result.result
            .fold(
                ifLeft = { e ->
                    val canRetry = e.getHttpCode().canRetry()
                    if (canRetry) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                },
                ifRight = {
                    Result.success()
                },
            )
    }

    override suspend fun getForegroundInfo() = createForegroundInfo(notificationId)

    //
    // Notification
    //

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(
        id: Int,
        downloadId: String,
        name: String,
        progress: Float? = null,
        downloaded: String? = null,
        total: String? = null,
    ): ForegroundInfo {
        val notification = kotlin.run {
            val channelId = createAttachmentDownloadChannel()

            // Progress
            val progressMax = PROGRESS_MAX
            val progressCurrent = progress?.times(progressMax)?.roundToInt()
                ?: progressMax
            val progressIndeterminate = progress == null

            // Action
            val cancelAction = kotlin.run {
                val cancelAction = kotlin.run {
                    val intent = AttachmentDownloadActionReceiver.cancel(
                        context = applicationContext,
                        downloadId = downloadId,
                    )
                    PendingIntent.getBroadcast(
                        applicationContext,
                        id,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
                val cancelTitle = "Cancel"
                NotificationCompat.Action.Builder(R.drawable.ic_cancel, cancelTitle, cancelAction)
                    .build()
            }

            NotificationCompat.Builder(applicationContext, channelId)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .addAction(cancelAction)
                .setContentTitle(name)
                .setGroup(WORK_ID)
                .setTicker(name)
                .run {
                    if (downloaded != null || total != null) {
                        val downloadedOrEmpty = downloaded ?: "--"
                        val totalOrEmpty = total ?: "--"
                        val info = "$downloadedOrEmpty / $totalOrEmpty"
                        setContentText(info)
                    } else {
                        this
                    }
                }
                .setProgress(progressMax, progressCurrent, progressIndeterminate)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        }
        return ForegroundInfo(
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createForegroundInfo(
        id: Int,
    ): ForegroundInfo {
        val notification = kotlin.run {
            val title = applicationContext.getString(R.string.notification_attachment_download_title)
            val channelId = createAttachmentDownloadChannel()
            NotificationCompat.Builder(applicationContext, channelId)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .setContentTitle(title)
                .setGroup(WORK_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        }
        return ForegroundInfo(
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createAttachmentDownloadChannel(): String {
        val channel = kotlin.run {
            val id =
                applicationContext.getString(R.string.notification_attachment_download_channel_id)
            val name =
                applicationContext.getString(R.string.notification_attachment_download_channel_name)
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        }
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
        return channel.id
    }
}
