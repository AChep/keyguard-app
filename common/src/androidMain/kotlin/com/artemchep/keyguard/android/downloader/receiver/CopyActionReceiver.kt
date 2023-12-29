package com.artemchep.keyguard.android.downloader.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class CopyActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ATTACHMENT_DOWNLOAD_CANCEL = ".ACTION_COPY"

        const val KEY_DOWNLOAD_ID = "download_id"

        fun cancel(
            context: Context,
            value: String,
        ): Intent = intent(
            context = context,
            suffix = ACTION_ATTACHMENT_DOWNLOAD_CANCEL,
        ) {
            putExtra(KEY_DOWNLOAD_ID, value)
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
                component = ComponentName(context, CopyActionReceiver::class.java)
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
                val value = intent.extras?.getString(KEY_DOWNLOAD_ID)
                    ?: return
                val windowCoroutineScope: WindowCoroutineScope by di.instance()
                val clipboardService: ClipboardService by di.instance()
                clipboardService.setPrimaryClip(value, false)
            }
        }
    }
}
