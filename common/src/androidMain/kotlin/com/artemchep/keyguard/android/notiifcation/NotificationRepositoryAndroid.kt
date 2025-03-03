package com.artemchep.keyguard.android.notiifcation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.artemchep.keyguard.android.MainActivity
import com.artemchep.keyguard.android.PendingIntents
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class NotificationRepositoryAndroid(
    private val context: Context,
) : NotificationRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
    )

    override fun post(
        notification: DNotification,
    ): IO<DNotificationKey?> = ioEffect {
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

    private fun createNotification(
        notification: DNotification,
    ): Notification {
        val icon = when (notification.channel) {
            DNotificationChannel.WATCHTOWER -> R.drawable.ic_security
        }
        val number = notification.number?.coerceAtLeast(0) ?: 0

        val channelId = createNotificationChannel(notification.channel)
        val intent = createPendingIntent()
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

    private fun createNotificationChannel(
        channel: DNotificationChannel,
    ) = when (channel) {
        DNotificationChannel.WATCHTOWER -> createNotificationChannelWatchtower()
    }

    private fun createNotificationChannelWatchtower(): String {
        val channelImportance = NotificationManager.IMPORTANCE_HIGH
        val channel = kotlin.run {
            val id = context.getString(R.string.notification_watchtower_channel_id)
            val name = context.getString(R.string.notification_watchtower_channel_name)
            NotificationChannel(id, name, channelImportance)
        }
        val nm = context.getSystemService<NotificationManager>()!!
        nm.createNotificationChannel(channel)
        return channel.id
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = MainActivity.getIntent(
            context = context,
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val code = PendingIntents.notification.obtainId()
        return PendingIntent.getActivity(context, code, intent, flags)
    }
}
