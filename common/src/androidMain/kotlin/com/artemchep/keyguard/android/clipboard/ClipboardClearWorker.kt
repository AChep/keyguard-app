package com.artemchep.keyguard.android.clipboard

import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.artemchep.keyguard.copy.clearPrimaryClip
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class ClipboardClearWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    companion object {
        private const val WORK_ID = "ClipboardClearWorker"

        fun enqueue(
            context: Context,
            delay: Duration,
        ) {
            val request = OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .build(),
                )
                .build()

            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(WORK_ID, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager
                .getInstance(context)
                .cancelUniqueWork(WORK_ID)
        }
    }

    private val clipboardManager: ClipboardManager? = applicationContext.getSystemService()

    override fun doWork(): Result {
        clipboardManager?.let(::clearPrimaryClip)
        return Result.success()
    }
}
