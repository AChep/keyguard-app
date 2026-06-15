package com.artemchep.keyguard.android.sshagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.artemchep.keyguard.android.MainActivity
import com.artemchep.keyguard.android.PendingIntents
import com.artemchep.keyguard.common.R

internal object SshAgentNotifications {
    fun createStatusChannel(
        context: Context,
    ): String {
        val channel = NotificationChannel(
            context.getString(R.string.notification_termux_ssh_channel_id),
            context.getString(R.string.notification_termux_ssh_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        val manager = context.getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(channel)
        return channel.id
    }

    fun createRequestChannel(
        context: Context,
    ): String {
        val channel = NotificationChannel(
            context.getString(R.string.notification_termux_ssh_request_channel_id),
            context.getString(R.string.notification_termux_ssh_request_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setShowBadge(true)
        val manager = context.getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(channel)
        return channel.id
    }

    fun createForegroundNotification(
        context: Context,
        contentText: String,
    ): Notification {
        val channelId = createStatusChannel(context)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(R.string.notification_termux_ssh_title))
            .setContentText(contentText)
            .setContentIntent(createMainActivityIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun createRequestNotification(
        context: Context,
        notificationTag: String,
        promptKind: AndroidSshAgentPromptKind,
        appName: String?,
    ): Notification {
        val channelId = createRequestChannel(context)
        val contentText = createRequestNotificationContentText(
            context = context,
            promptKind = promptKind,
            appName = appName,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(R.string.notification_termux_ssh_title))
            .setContentText(contentText)
            .setContentIntent(createRequestActivityIntent(context))
            .setDeleteIntent(createRequestDeleteIntent(context, notificationTag))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun createRequestNotificationContentText(
        context: Context,
        promptKind: AndroidSshAgentPromptKind,
        appName: String?,
    ): String {
        val normalizedAppName = appName?.takeIf { it.isNotBlank() }
        return when (promptKind) {
            AndroidSshAgentPromptKind.Unlock -> {
                if (normalizedAppName != null) {
                    context.getString(R.string.notification_termux_ssh_content_unlock_app, normalizedAppName)
                } else {
                    context.getString(R.string.notification_termux_ssh_content_unlock)
                }
            }

            AndroidSshAgentPromptKind.Sign -> {
                if (normalizedAppName != null) {
                    context.getString(R.string.notification_termux_ssh_content_sign_app, normalizedAppName)
                } else {
                    context.getString(R.string.notification_termux_ssh_content_sign)
                }
            }
        }
    }

    fun createServiceStartupNotification(
        context: Context,
        serviceIntent: Intent,
        appName: String?,
    ): Notification {
        val channelId = createRequestChannel(context)
        val contentText = createStartupNotificationContentText(
            context = context,
            appName = appName,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(R.string.notification_termux_ssh_title))
            .setContentText(contentText)
            .setContentIntent(createRecoveryActivityIntent(context, serviceIntent))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
    }

    private fun createStartupNotificationContentText(
        context: Context,
        appName: String?,
    ): String {
        val normalizedAppName = appName?.takeIf { it.isNotBlank() }
        return if (normalizedAppName != null) {
            context.getString(R.string.notification_termux_ssh_content_tap_to_start_app, normalizedAppName)
        } else {
            context.getString(R.string.notification_termux_ssh_content_tap_to_start)
        }
    }

    fun createServiceRejectedNotification(
        context: Context,
    ): Notification {
        val channelId = createRequestChannel(context)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(R.string.notification_termux_ssh_request_rejected_title))
            .setContentIntent(createMainActivityIntent(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
    }

    private fun createMainActivityIntent(
        context: Context,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = PendingIntents.notification.obtainId()
        return PendingIntent.getActivity(
            context,
            requestCode,
            MainActivity.getIntent(context),
            flags,
        )
    }

    private fun createRecoveryActivityIntent(
        context: Context,
        serviceIntent: Intent,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = PendingIntents.notification.obtainId()
        return PendingIntent.getActivity(
            context,
            requestCode,
            SshRequestActivity.getIntent(
                context = context,
                serviceIntent = serviceIntent,
            ),
            flags,
        )
    }

    private fun createRequestActivityIntent(
        context: Context,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = PendingIntents.notification.obtainId()
        return PendingIntent.getActivity(
            context,
            requestCode,
            SshRequestActivity.getIntent(context),
            flags,
        )
    }

    private fun createRequestDeleteIntent(
        context: Context,
        notificationTag: String,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = PendingIntents.notification.obtainId()
        val intent = SshRequestNotificationActionReceiver.dismiss(
            context = context,
            notificationTag = notificationTag,
        )
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            flags,
        )
    }
}
