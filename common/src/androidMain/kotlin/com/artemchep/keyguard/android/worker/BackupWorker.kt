package com.artemchep.keyguard.android.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupRunProgress
import com.artemchep.keyguard.common.service.backup.BackupRunProgressDetails
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.backup.BackupStep
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.getString
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), DIAware {
    companion object {
        private const val WORK_ID = "VaultBackupWorker"

        private const val PROGRESS_MAX = 100

        fun enqueueOnce(
            context: Context,
            config: BackupConfig,
            delayMs: Long = 0L,
        ): Operation {
            val workManager = WorkManager.getInstance(context)
            if (!config.canRun()) {
                return workManager.cancelUniqueWork(WORK_ID)
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (config.requiresNetwork()) {
                        NetworkType.CONNECTED
                    } else {
                        NetworkType.NOT_REQUIRED
                    },
                )
                .build()
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            return workManager.enqueueUniqueWork(
                WORK_ID,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun cancel(
            context: Context,
        ): Operation = WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_ID)
    }

    override val di by closestDI { applicationContext }

    private val notificationManager = context.getSystemService<NotificationManager>()!!

    private val notificationId = Notifications.export.obtainId()

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        val backupRunService: BackupRunService by instance()
        backupRunService.runAutomatic(
            progressReporter = { progress ->
                setForeground(createForegroundInfo(progress))
            },
        )
        return Result.success()
    }

    override suspend fun getForegroundInfo() = createForegroundInfo()

    private suspend fun createForegroundInfo(
        progress: BackupRunProgress? = null,
    ): ForegroundInfo {
        val title = getString(Res.string.notification_vault_backup_title)
        val progressValue = progress
            ?.details
            ?.progressPercentage()
        val progressCurrent = progressValue
            ?.times(PROGRESS_MAX)
            ?.roundToInt()
            ?: PROGRESS_MAX
        val progressIndeterminate = progressValue == null

        val channelId = createBackupChannel()
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentTitle(title)
            .run {
                val text = progress?.notificationText()
                if (text != null) {
                    setContentText(text)
                } else {
                    this
                }
            }
            .setTicker(title)
            .setProgress(PROGRESS_MAX, progressCurrent, progressIndeterminate)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private suspend fun BackupRunProgress.notificationText(): String {
        val step = step.notificationText()
        val details = details.notificationText()
        return if (details != null) {
            "$step - $details"
        } else {
            step
        }
    }

    private suspend fun BackupStep.notificationText(): String = when (this) {
        BackupStep.Preparing -> getString(
            Res.string.notification_vault_backup_step_preparing,
        )
        BackupStep.OpeningRepository -> getString(
            Res.string.notification_vault_backup_step_opening_repository,
        )
        BackupStep.ExportingVault -> getString(
            Res.string.notification_vault_backup_step_exporting_vault,
        )
        BackupStep.ScanningAttachments -> getString(
            Res.string.notification_vault_backup_step_scanning_attachments,
        )
        BackupStep.BackingUpAttachments -> getString(
            Res.string.notification_vault_backup_step_backing_up_attachments,
        )
        BackupStep.WritingIndex -> getString(
            Res.string.notification_vault_backup_step_writing_index,
        )
        BackupStep.WritingSnapshot -> getString(
            Res.string.notification_vault_backup_step_writing_snapshot,
        )
        BackupStep.ApplyingRetention -> getString(
            Res.string.notification_vault_backup_step_applying_retention,
        )
    }

    private suspend fun BackupRunProgressDetails.notificationText(): String? {
        val downloaded = downloadedBytes
        val total = totalBytes
        if (downloaded != null || total != null) {
            val downloadedText = downloaded
                ?.let(::humanReadableByteCountSI)
                ?: "--"
            val totalText = total
                ?.let(::humanReadableByteCountSI)
                ?: "--"
            return getString(
                Res.string.notification_vault_backup_progress_bytes,
                downloadedText,
                totalText,
            )
        }

        val processed = itemsProcessed
        val items = itemsTotal
        return if (processed != null && items != null && items > 0) {
            getString(
                Res.string.notification_vault_backup_progress_items,
                processed,
                items,
            )
        } else {
            null
        }
    }

    private fun BackupRunProgressDetails.progressPercentage(): Float? {
        val downloaded = downloadedBytes
        val total = totalBytes
        if (downloaded != null && total != null && total > 0L) {
            return (downloaded.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f..1f)
        }

        val processed = itemsProcessed
        val items = itemsTotal
        if (processed != null && items != null && items > 0) {
            return (processed.toDouble() / items.toDouble())
                .toFloat()
                .coerceIn(0f..1f)
        }

        return null
    }

    private fun createBackupChannel(): String {
        val channel = kotlin.run {
            val id =
                applicationContext.getString(R.string.notification_vault_backup_channel_id)
            val name =
                applicationContext.getString(R.string.notification_vault_backup_channel_name)
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        }
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
        return channel.id
    }
}
