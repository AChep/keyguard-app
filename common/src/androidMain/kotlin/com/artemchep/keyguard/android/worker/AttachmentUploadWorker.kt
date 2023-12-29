package com.artemchep.keyguard.android.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artemchep.keyguard.android.worker.util.SessionWorker
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetCiphers
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance

class AttachmentUploadWorker(
    context: Context,
    params: WorkerParameters,
) : SessionWorker(context, params), DIAware {
    companion object {
        private const val WORK_ID = "AttachmentUploadWorker"

        val constraints
            get() = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        fun enqueueOnce(context: Context): Operation {
            val uploadWorkRequest = OneTimeWorkRequestBuilder<AttachmentUploadWorker>()
                .setConstraints(constraints)
                .build()
            return WorkManager
                .getInstance(context)
                .enqueueUniqueWork(WORK_ID, ExistingWorkPolicy.APPEND_OR_REPLACE, uploadWorkRequest)
        }
    }

    private val notificationManager = context.getSystemService<NotificationManager>()!!

    override suspend fun doWork(): Result {
        val foregroundInfo = createForegroundInfo("TODO")
        setForeground(foregroundInfo)
        return super.doWork()
    }

    private data class UploadAttachmentRequest(
        val key: CompositeKey,
        val attachment: DSecret.Attachment.Local,
    ) {
        data class CompositeKey(
            val accountId: String,
            val cipherId: String,
            val organizationId: String?,
            val attachmentId: String,
        )
    }

    private data class UploadAttachmentJob(
        val url: String,
        val job: Job,
    )

    private class UploadManager(
        private val scope: CoroutineScope,
    ) {
        fun queue(requests: List<UploadAttachmentRequest>) {
        }
    }

    private sealed interface UploadAttachmentIntent {
        data class Put(
            val requests: List<UploadAttachmentRequest>,
        ) : UploadAttachmentIntent

        data class Complete(
            val requests: UploadAttachmentRequest,
        ) : UploadAttachmentIntent
    }

    override suspend fun DI.doWork(): Result {
        val semaphore = Semaphore(2)
        coroutineScope {
            createUploadAttachmentRequestsFlow()
                .scan(
                    initial = persistentMapOf<UploadAttachmentRequest.CompositeKey, UploadAttachmentJob>(),
                ) { state, requests ->
                    val builder = state.builder()

                    requests.forEach { request ->
                        val existingValue = builder[request.key]
                        @Suppress("UnnecessaryVariable")
                        if (existingValue != null) {
                            val shouldKeep = existingValue.url == request.attachment.url
                            if (shouldKeep) return@forEach

                            // Stop the current upload job and replace it
                            // with a new one.
                            existingValue.job.cancel()
                        }

                        val newValue = UploadAttachmentJob(
                            url = request.attachment.url,
                            job = launch {
                                semaphore.withPermit {
                                    dow(request)
                                }
                            },
                        )
                        builder[request.key] = newValue
                    }

                    builder.build()
                }
                .drop(1) // initial
                // We want to stop the coroutine after there's no
                // more requests to take on.
                .takeWhile {
                    it.values
                        .any { !it.job.isCompleted }
                }
                .collect()
        }
        Log.e("attachment", "complete!")
        return Result.success()
    }

    private fun DI.createUploadAttachmentRequestsFlow() = direct.instance<GetCiphers>()
        .invoke() // flow of list of ciphers
        .map { ciphers ->
            val pendingRequests = ciphers
                .asSequence()
                .flatMap { cipher ->
                    cipher
                        .attachments
                        .asSequence()
                        .mapNotNull { it as? DSecret.Attachment.Local }
                        .map { attachment ->
                            val key = UploadAttachmentRequest.CompositeKey(
                                accountId = cipher.accountId,
                                cipherId = cipher.id,
                                organizationId = cipher.organizationId,
                                attachmentId = attachment.id,
                            )
                            UploadAttachmentRequest(
                                key = key,
                                attachment = attachment,
                            )
                        }
                }
                .toList()
            pendingRequests
        }

    private suspend fun dow(request: UploadAttachmentRequest) {
        request
        delay(5000L)
    }

    //
    // Notification
    //

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val notification = kotlin.run {
            val title = applicationContext.getString(R.string.notification_attachment_upload_title)
            val channelId = createAttachmentUploadChannel()
            NotificationCompat.Builder(applicationContext, channelId)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(progress)
                .setProgress(1, 1, true)
                .setSmallIcon(R.drawable.ic_upload)
                .setOngoing(true)
                .build()
        }
        val id =
            applicationContext.resources.getInteger(R.integer.notification_attachment_upload_id)
        return ForegroundInfo(
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createAttachmentUploadChannel(): String {
        val channel = kotlin.run {
            val id =
                applicationContext.getString(R.string.notification_attachment_upload_channel_id)
            val name =
                applicationContext.getString(R.string.notification_attachment_upload_channel_name)
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        }
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
        return channel.id
    }
}
