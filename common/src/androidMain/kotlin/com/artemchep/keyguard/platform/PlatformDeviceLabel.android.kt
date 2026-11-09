package com.artemchep.keyguard.platform

import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal actual suspend fun platformDeviceLabel(context: LeContext): String? = withContext(Dispatchers.IO) {
    firstDeviceLabel(
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
        },
        { manufacturerModelDeviceLabel(Build.MANUFACTURER, Build.MODEL) },
        { Build.MODEL },
    )
}
