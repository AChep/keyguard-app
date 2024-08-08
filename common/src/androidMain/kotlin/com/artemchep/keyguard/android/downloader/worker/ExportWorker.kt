package com.artemchep.keyguard.android.downloader.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.android.downloader.receiver.VaultExportActionReceiver
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import org.jetbrains.compose.resources.getString
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import kotlin.math.roundToInt

class ExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), DIAware {
    companion object {
        private const val WORK_ID = "VaultExportWorker"

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
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
                .setInputData(data)
                .build()
            val workId = buildWorkKey(args.exportId)
            return WorkManager
                .getInstance(context)
                .enqueueUniqueWork(workId, ExistingWorkPolicy.KEEP, request)
        }

        private fun buildWorkKey(id: String) = "$WORK_ID:$id"
    }

    data class Args(
        val exportId: String,
    ) {
        companion object {
            private const val KEY_EXPORT_ID = "export_id"

            fun of(data: Data) = Args(
                exportId = data.getString(KEY_EXPORT_ID)!!,
            )
        }

        fun populate(builder: Data.Builder) {
            builder.putString(KEY_EXPORT_ID, exportId)
        }
    }

    override val di by closestDI { applicationContext }

    private val translator by lazy {
        val ctx = LeContext(applicationContext)
        TranslatorScope.of(ctx)
    }

    private val notificationManager = context.getSystemService<NotificationManager>()!!

    private var notificationId = Notifications.export.obtainId()

    override suspend fun doWork(): Result = run {
        val args = Args.of(inputData)
        internalDoWork(
            notificationId = notificationId,
            args = args,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun internalDoWork(
        notificationId: Int,
        args: Args,
    ): Result {
        val ea: GetVaultSession by instance()
        val s = ea.valueOrNull as? MasterSession.Key
            ?: return Result.success()

        val exportManager: ExportManager by s.di.instance()
        val exportProgressFlow = exportManager
            .getProgressFlowByExportId(exportId = args.exportId)
            // Return None if the progress flow doesn't
            // exist anymore. Notice how we use the concat here,
            // this is intended.
            .flatMapConcat { flow ->
                flow ?: flowOf(DownloadProgress.None)
            }

        val title = applicationContext.getString(R.string.notification_vault_export_title)
        val result = exportProgressFlow
            .onStart {
                val foregroundInfo = createForegroundInfo(
                    id = notificationId,
                    exportId = args.exportId,
                    name = title,
                    progress = null,
                )
                setForeground(foregroundInfo)
            }
            .onEach { progress ->
                when (progress) {
                    is DownloadProgress.None -> {
                        // Do nothing
                    }

                    is DownloadProgress.Loading -> {
                        val downloadedFormatted = progress.downloaded
                            ?.let(::humanReadableByteCountSI)
                        val totalFormatted = progress.total
                            ?.let(::humanReadableByteCountSI)

                        val p = progress.percentage
                        val foregroundInfo = createForegroundInfo(
                            id = notificationId,
                            exportId = args.exportId,
                            name = title,
                            progress = p,
                            downloaded = downloadedFormatted,
                            total = totalFormatted,
                        )
                        setForeground(foregroundInfo)
                    }

                    is DownloadProgress.Complete -> {
                        // Do nothing
                    }
                }
            }
            // complete once we finish the download
            .transformWhile { progress ->
                emit(progress) // always emit progress
                progress !is DownloadProgress.Complete &&
                        progress !is DownloadProgress.None
            }
            .last()
        // None means that the progress flow doesn't exist
        // anymore. This is most likely to happen because
        // someone has cancelled the export.
        if (result is DownloadProgress.None) {
            return Result.success()
        }

        require(result is DownloadProgress.Complete)
        // Send a complete notification.
        result.result.fold(
            ifLeft = { e ->
                sendFailureNotification(
                    exportId = args.exportId,
                    reason = e,
                )
            },
            ifRight = {
                sendSuccessNotification(
                    exportId = args.exportId,
                )
            },
        )
        return result.result
            .fold(
                ifLeft = {
                    // We don't want to automatically retry exporting a
                    // vault, just notify a user and bail out.
                    Result.success()
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

    private suspend fun sendFailureNotification(
        exportId: String,
        reason: Throwable,
    ) = sendCompleteNotification(exportId) { builder ->
        val name = getString(Res.string.exportaccount_export_failure)
        val message = getErrorReadableMessage(reason, translator)
        builder
            .setContentTitle(name)
            .setContentText(message.text ?: message.title)
            .setTicker(name)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
    }

    private suspend fun sendSuccessNotification(
        exportId: String,
    ) = sendCompleteNotification(exportId) { builder ->
        val name = getString(Res.string.exportaccount_export_success)
        builder
            .setContentTitle(name)
            .setTicker(name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
    }

    private inline fun sendCompleteNotification(
        exportId: String,
        block: (NotificationCompat.Builder) -> NotificationCompat.Builder,
    ) {
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .run(block)
            .setGroup(WORK_ID)
            .build()
        notificationManager.notify(exportId, notificationId, notification)
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(
        id: Int,
        exportId: String,
        name: String,
        progress: Float? = null,
        downloaded: String? = null,
        total: String? = null,
    ): ForegroundInfo {
        val notification = kotlin.run {
            val channelId = createNotificationChannel()

            // Progress
            val progressMax = PROGRESS_MAX
            val progressCurrent = progress?.times(progressMax)?.roundToInt()
                ?: progressMax
            val progressIndeterminate = progress == null

            // Action
            val cancelAction = kotlin.run {
                val cancelAction = kotlin.run {
                    val intent = VaultExportActionReceiver.cancel(
                        context = applicationContext,
                        exportId = exportId,
                    )
                    PendingIntent.getBroadcast(
                        applicationContext,
                        id,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
                val cancelTitle = applicationContext.getString(android.R.string.cancel)
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
            val title = applicationContext.getString(R.string.notification_vault_export_title)
            val channelId = createNotificationChannel()
            NotificationCompat.Builder(applicationContext, channelId)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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

    private fun createNotificationChannel(): String {
        val channel = kotlin.run {
            val id =
                applicationContext.getString(R.string.notification_vault_export_channel_id)
            val name =
                applicationContext.getString(R.string.notification_vault_export_channel_name)
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        }
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
        return channel.id
    }
}
