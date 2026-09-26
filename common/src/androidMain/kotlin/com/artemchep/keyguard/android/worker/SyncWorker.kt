package com.artemchep.keyguard.android.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.artemchep.keyguard.android.worker.util.SessionWorker
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.SyncAll
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.syncRequiresNetwork
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.resolve

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : SessionWorker(context, params), KeyguardKoinOwner {
    companion object {
        private const val WORK_ID_PREFIX = "vault-sync:"
        private const val NETWORK_FALLBACK_WORK_ID_PREFIX = "vault-sync-network-fallback:"

        private const val KEY_ACCOUNT_IDS = "account_ids"

        /**
         * Set when a handled failure of this request should enqueue one
         * connected retry, and a success should cancel a pending one.
         */
        private const val KEY_NETWORK_FALLBACK_ELIGIBLE = "network_fallback_eligible"

        suspend fun enqueueOnce(
            context: Context,
            accounts: List<ServiceToken>,
        ) {
            val workManager = WorkManager.getInstance(context)
            accounts.forEach { account ->
                val networkType = if (account.syncRequiresNetwork()) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.NOT_REQUIRED
                }
                workManager.enqueue(
                    workId = WORK_ID_PREFIX + account.id,
                    policy = ExistingWorkPolicy.REPLACE,
                    accountId = AccountId(account.id),
                    networkType = networkType,
                    networkFallbackEligible = account.mayNeedNetworkFallback(),
                )
            }
        }

        private suspend fun WorkManager.enqueue(
            workId: String,
            policy: ExistingWorkPolicy,
            accountId: AccountId,
            networkType: NetworkType,
            networkFallbackEligible: Boolean,
        ) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build(),
                )
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_ACCOUNT_IDS, arrayOf(accountId.id))
                        .putBoolean(KEY_NETWORK_FALLBACK_ELIGIBLE, networkFallbackEligible)
                        .build(),
                )
                .build()
            enqueueUniqueWork(workId, policy, request).await()
        }
    }

    private val notificationManager = context.getSystemService<NotificationManager>()!!

    /**
     * New requests contain one account. Retain the array and empty-as-all
     * semantics so persisted requests from older versions can still run.
     */
    private val accountIds by lazy {
        inputData.getStringArray(KEY_ACCOUNT_IDS)
            ?.asSequence()
            ?.map { AccountId(it) }
            ?.toSet().orEmpty()
    }

    private val networkFallbackEligible by lazy {
        inputData.getBoolean(KEY_NETWORK_FALLBACK_ELIGIBLE, false)
    }

    override suspend fun doWork(session: MasterSession.Key): Result {
        val io = session.session.resolve {
            if (accountIds.isEmpty()) {
                val syncAll = get<SyncAll>()
                syncAll()
                    .map { Unit }
            } else {
                val syncById = get<SyncById>()
                accountIds
                    .map(syncById)
                    .parallel()
                    .map { Unit }
            }
        } ?: return Result.success()
        // Sync implementations record handled failures. Complete this request so
        // a later replacement can run. attempt() still propagates fatal errors
        // and cancellation.
        val syncFailed = io
            .attempt()
            .bind()
            .isLeft()
        if (networkFallbackEligible) {
            accountIds.singleOrNull()?.let { accountId ->
                updateNetworkFallback(
                    accountId = accountId,
                    syncFailed = syncFailed,
                )
            }
        }
        return Result.success()
    }

    private suspend fun updateNetworkFallback(
        accountId: AccountId,
        syncFailed: Boolean,
    ) {
        runCatchingNonFatal {
            val workManager = WorkManager.getInstance(applicationContext)
            val workId = NETWORK_FALLBACK_WORK_ID_PREFIX + accountId.id
            if (syncFailed) {
                workManager.enqueue(
                    workId = workId,
                    policy = ExistingWorkPolicy.KEEP,
                    accountId = accountId,
                    networkType = NetworkType.CONNECTED,
                    // A failed fallback must not enqueue another one.
                    networkFallbackEligible = false,
                )
            } else {
                workManager.cancelUniqueWork(workId).await()
            }
        }
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

/**
 * Document providers may back a `content://` file with a remote copy, so a
 * failed offline sync is worth one more attempt once connected.
 */
private fun ServiceToken.mayNeedNetworkFallback(): Boolean {
    val location = (this as? KeePassToken)?.database?.location as? FileLocation.Local
        ?: return false
    return location.uri.toUri().scheme == ContentResolver.SCHEME_CONTENT
}
