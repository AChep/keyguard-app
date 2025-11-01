package com.artemchep.keyguard.android.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

fun Context.launchNotificationChannelSettingsOrThrow(
    notificationChannelId: String,
) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, notificationChannelId)
    }
    startActivity(intent)
}

fun Context.launchAutofillSettingsOrThrow(
) {
    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
        data = "package:$packageName".toUri()
    }
    startActivity(intent)
}
