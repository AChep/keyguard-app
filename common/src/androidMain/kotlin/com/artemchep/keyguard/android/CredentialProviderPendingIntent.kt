package com.artemchep.keyguard.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

fun createCredentialProviderPendingIntent(
    context: Context,
    intent: Intent,
): PendingIntent {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    val requestCode = PendingIntents.credential.obtainId()
    return PendingIntent.getActivity(context, requestCode, intent, flags)
}
