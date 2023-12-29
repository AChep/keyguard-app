package com.artemchep.keyguard.android.downloader.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class AttachmentDownloadActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ATTACHMENT_DOWNLOAD_CANCEL = ".ACTION_ATTACHMENT_DOWNLOAD_CANCEL"

        const val KEY_DOWNLOAD_ID = "download_id"

        fun cancel(
            context: Context,
            downloadId: String,
        ): Intent = intent(
            context = context,
            suffix = ACTION_ATTACHMENT_DOWNLOAD_CANCEL,
        ) {
            putExtra(KEY_DOWNLOAD_ID, downloadId)
        }

        fun intent(
            context: Context,
            suffix: String,
            builder: Intent.() -> Unit = {},
        ): Intent {
            val action = kotlin.run {
                val packageName = context.packageName
                "$packageName$suffix"
            }
            return Intent(action).apply {
                component = ComponentName(context, AttachmentDownloadActionReceiver::class.java)
                builder()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
            ?: return
        val di by closestDI { context }
        when {
            action.endsWith(ACTION_ATTACHMENT_DOWNLOAD_CANCEL) -> {
                val downloadId = intent.extras?.getString(KEY_DOWNLOAD_ID)
                    ?: return
                val windowCoroutineScope: WindowCoroutineScope by di.instance()
                val removeIo = kotlin.run {
                    val request = RemoveAttachmentRequest.ByDownloadId(
                        downloadId = downloadId,
                    )
                    val removeAttachment: RemoveAttachment by di.instance()
                    removeAttachment(listOf(request))
                }
                removeIo.launchIn(windowCoroutineScope)
            }
        }
    }
}
