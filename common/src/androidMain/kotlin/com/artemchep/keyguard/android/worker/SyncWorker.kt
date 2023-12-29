package com.artemchep.keyguard.android.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artemchep.keyguard.android.worker.util.SessionWorker
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.SyncAll
import com.artemchep.keyguard.common.usecase.SyncById
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : SessionWorker(context, params), DIAware {
    companion object {
        private const val WORK_ID = "AttachmentUploadWorker"

        private const val KEY_ACCOUNT_IDS = "account_ids"

        val constraints
            get() = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        fun enqueueOnce(
            context: Context,
            accounts: Set<AccountId> = emptySet(),
        ): Operation {
            val accountIdsArray = accounts
                .map { it.id }
                .toTypedArray()
            val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_ACCOUNT_IDS, accountIdsArray)
                        .build(),
                )
                .build()
            return WorkManager
                .getInstance(context)
                .enqueueUniqueWork(WORK_ID, ExistingWorkPolicy.APPEND_OR_REPLACE, uploadWorkRequest)
        }
    }

    private val notificationManager = context.getSystemService<NotificationManager>()!!

    /**
     * Set of accounts to update. When empty, we should
     * update all existing accounts.
     */
    private val accountIds by lazy {
        inputData.getStringArray(KEY_ACCOUNT_IDS)
            ?.asSequence()
            ?.map { AccountId(it) }
            ?.toSet().orEmpty()
    }

    override suspend fun DI.doWork(): Result {
//        if (BuildConfig.DEBUG) {
//            AttachmentUploadWorker.enqueueOnce(applicationContext)
//        }

        val io = if (accountIds.isEmpty()) {
            val syncAll = direct.instance<SyncAll>()
            syncAll()
                .map { Unit }
        } else {
            val syncById = direct.instance<SyncById>()
            accountIds
                .map(syncById)
                .parallel()
                .map { Unit }
        }
        return io
            .attempt()
            .bind()
            .fold(
                ifLeft = {
                    Result.failure()
                },
                ifRight = {
                    Result.success()
                },
            )
    }

    override suspend fun getForegroundInfo() = createForegroundInfo()

    //
    // Notification
    //

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(): ForegroundInfo {
        val notification = kotlin.run {
            val title = applicationContext.getString(R.string.notification_sync_vault_title)
            val channelId = createSyncVaultChannel()
            NotificationCompat.Builder(applicationContext, channelId)
                .setContentTitle(title)
                .setTicker(title)
                .setSmallIcon(R.drawable.ic_sync)
                .setOngoing(true)
                .build()
        }
        val id =
            applicationContext.resources.getInteger(R.integer.notification_sync_vault_id)
        return ForegroundInfo(
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createSyncVaultChannel(): String {
        val channel = kotlin.run {
            val id =
                applicationContext.getString(R.string.notification_sync_vault_channel_id)
            val name =
                applicationContext.getString(R.string.notification_sync_vault_channel_name)
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        }
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
        return channel.id
    }
}
