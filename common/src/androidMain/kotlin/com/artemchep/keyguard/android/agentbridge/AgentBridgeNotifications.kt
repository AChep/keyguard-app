package com.artemchep.keyguard.android.agentbridge

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

/** The texts of one agent's bridge notifications. */
internal class AgentBridgeText(
    val requestChannelId: Int,
    val requestChannelName: Int,
    val title: Int,
    val connecting: Int,
    val error: Int,
    val disabled: Int,
    val request: Int,
    val requestApp: Int,
)

/** The title and text of the service notification. */
internal data class AgentBridgeStatusText(
    val title: Int,
    val content: Int,
)

/** Names the one active agent, or every agent once several bridges overlap. */
internal fun agentBridgeStatusText(
    bridges: Set<AgentBridge>,
): AgentBridgeStatusText {
    require(bridges.isNotEmpty()) { "The service notification needs an active bridge." }
    val bridge = bridges.singleOrNull()
    return if (bridge != null) {
        AgentBridgeStatusText(
            title = bridge.text.title,
            content = bridge.text.connecting,
        )
    } else {
        AgentBridgeStatusText(
            title = R.string.notification_agent_bridge_title,
            content = R.string.notification_agent_bridge_content,
        )
    }
}

internal object AgentBridgeNotifications {
    fun createStatusChannel(
        context: Context,
    ): String {
        val channel = NotificationChannel(
            context.getString(R.string.notification_agent_bridge_status_channel_id),
            context.getString(R.string.notification_agent_bridge_status_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        val manager = context.getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(channel)
        return channel.id
    }

    fun createRequestChannel(
        context: Context,
        bridge: AgentBridge,
    ): String {
        val channel = NotificationChannel(
            context.getString(bridge.text.requestChannelId),
            context.getString(bridge.text.requestChannelName),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setShowBadge(true)
        val manager = context.getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(channel)
        return channel.id
    }

    fun createForegroundNotification(
        context: Context,
        bridges: Set<AgentBridge>,
    ): Notification {
        val channelId = createStatusChannel(context)
        val text = agentBridgeStatusText(bridges)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(text.title))
            .setContentText(context.getString(text.content))
            .setContentIntent(createMainActivityIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun createRequestNotification(
        context: Context,
        bridge: AgentBridge,
        notificationTag: String,
        appName: String?,
    ): Notification {
        val channelId = createRequestChannel(context, bridge)
        val contentText = createRequestNotificationContentText(
            context = context,
            bridge = bridge,
            appName = appName,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(bridge.text.title))
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
        bridge: AgentBridge,
        appName: String?,
    ): String {
        val normalizedAppName = appName?.takeIf { it.isNotBlank() }
        return if (normalizedAppName != null) {
            context.getString(bridge.text.requestApp, normalizedAppName)
        } else {
            context.getString(bridge.text.request)
        }
    }

    fun createServiceStartupNotification(
        context: Context,
        bridge: AgentBridge,
        serviceIntent: Intent,
        appName: String?,
    ): Notification {
        val channelId = createRequestChannel(context, bridge)
        val contentText = createStartupNotificationContentText(
            context = context,
            appName = appName,
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(bridge.text.title))
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
        bridge: AgentBridge,
    ): Notification {
        val channelId = createRequestChannel(context, bridge)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_lock_outline)
            .setContentTitle(context.getString(bridge.text.disabled))
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
            AgentRequestActivity.getIntent(
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
            AgentRequestActivity.getIntent(context),
            flags,
        )
    }

    private fun createRequestDeleteIntent(
        context: Context,
        notificationTag: String,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = PendingIntents.notification.obtainId()
        val intent = AgentRequestNotificationActionReceiver.dismiss(
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
