package com.artemchep.keyguard.android.util

import android.content.Context
import androidx.core.app.NotificationManagerCompat

fun canPostNotifications(
    context: Context,
): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
