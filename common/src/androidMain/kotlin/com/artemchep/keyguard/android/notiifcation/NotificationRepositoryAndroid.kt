package com.artemchep.keyguard.android.notiifcation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.artemchep.keyguard.android.MainActivity
import com.artemchep.keyguard.android.PendingIntents
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.notification_app_update_channel_name
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.getString

class NotificationRepositoryAndroid(
    private val context: Context,
) : NotificationRepository {

    // The Compose string resource cache can deadlock
    // the main thread, so the notification is created off it.
    override fun post(
        notification: DNotification,
    ): IO<DNotificationKey?> = ioEffect(Dispatchers.Default) {
        val notificationManager = context.getSystemService<NotificationManager>()
            ?: return@ioEffect null
        notificationManager.notify(
            notification.tag,
            notification.id.value,
            createNotification(notification),
        )
        return@ioEffect DNotificationKey(
            id = notification.id.value,
            tag = notification.tag,
        )
    }

    override fun delete(
        key: DNotificationKey,
    ): IO<Unit> = ioEffect {
        val notificationManager = context.getSystemService<NotificationManager>()
            ?: return@ioEffect
        notificationManager.cancel(
            key.tag,
            key.id,
        )
    }

    private suspend fun createNotification(
        notification: DNotification,
    ): Notification {
        val icon = when (notification.channel) {
            DNotificationChannel.WATCHTOWER,
            DNotificationChannel.KEYGUARD_DEVICES,
            -> R.drawable.ic_security

            DNotificationChannel.APP_UPDATES -> R.drawable.ic_download
        }
        val number = notification.number?.coerceAtLeast(0) ?: 0

        val channelId = createNotificationChannel(notification.channel)
        val intent = notification.url
            ?.let { url ->
                val viewIntent = Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                createPendingIntent(viewIntent)
            }
            ?: createPendingIntent()
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(notification.title)
            .setSmallIcon(icon)
            .setNumber(number)
            .setAutoCancel(true)
            .setContentIntent(intent)
        if (!notification.text.isNullOrBlank()) {
            builder.setContentText(notification.text)
        }
        return builder
            .build()
    }

    private suspend fun createNotificationChannel(
        channel: DNotificationChannel,
    ) = when (channel) {
        DNotificationChannel.WATCHTOWER -> createNotificationChannel(
            id = context.getString(R.string.notification_watchtower_channel_id),
            name = context.getString(R.string.notification_watchtower_channel_name),
        )

        DNotificationChannel.KEYGUARD_DEVICES -> createNotificationChannel(
            id = context.getString(R.string.notification_keyguard_devices_channel_id),
            name = context.getString(R.string.notification_keyguard_devices_channel_name),
        )

        DNotificationChannel.APP_UPDATES -> createNotificationChannel(
            id = context.getString(R.string.notification_app_update_channel_id),
            name = getString(Res.string.notification_app_update_channel_name),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    private fun createNotificationChannel(
        id: String,
        name: String,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
    ): String {
        val channel = NotificationChannel(id, name, importance)
        val nm = context.getSystemService<NotificationManager>()!!
        nm.createNotificationChannel(channel)
        return channel.id
    }

    private fun createPendingIntent(
        intent: Intent = MainActivity.getIntent(
            context = context,
        ),
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val code = PendingIntents.notification.obtainId()
        return PendingIntent.getActivity(context, code, intent, flags)
    }
}
