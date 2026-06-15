package com.artemchep.keyguard.android.sshagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SshRequestNotificationActionReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_DISMISS = ".ACTION_SSH_REQUEST_NOTIFICATION_DISMISS"

        private const val KEY_NOTIFICATION_TAG = "notification_tag"

        /**
         * Upon executing dismissing the SSH agent request marked with
         * the notification tag.
         */
        fun dismiss(
            context: Context,
            notificationTag: String,
        ): Intent = intent(
            context = context,
            suffix = ACTION_DISMISS,
        ) {
            putExtra(KEY_NOTIFICATION_TAG, notificationTag)
        }

        private fun intent(
            context: Context,
            suffix: String,
            builder: Intent.() -> Unit = {},
        ): Intent {
            return Intent(context, SshRequestNotificationActionReceiver::class.java).apply {
                action = "${context.packageName}$suffix"
                builder()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        when {
            action == null -> {
                // Do nothing.
            }
            action.endsWith(ACTION_DISMISS) -> {
                handleDismissAction(
                    context = context,
                    intent = intent,
                )
            }
            else -> {
                // Do nothing
            }
        }
    }

    private fun handleDismissAction(
        context: Context,
        intent: Intent,
    ) {
        val notificationTag = intent.extras?.getString(KEY_NOTIFICATION_TAG)
            ?: return
        SshRequestCoordinator.dismissRequest(notificationTag)
    }
}
