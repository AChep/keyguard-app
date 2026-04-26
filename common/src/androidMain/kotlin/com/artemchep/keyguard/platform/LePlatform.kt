package com.artemchep.keyguard.platform

import android.content.pm.PackageManager
import android.os.Build
import com.artemchep.keyguard.android.BaseApp

val CurrentPlatformImpl by lazy {
    val isChromebook = Build.DEVICE.orEmpty()
        .matches(".+_cheets|cheets_.+".toRegex())
    val isWatch = BaseApp.context
        ?.packageManager?.hasSystemFeature(PackageManager.FEATURE_WATCH) == true
    Platform.Mobile.Android(
        isChromebook = isChromebook,
        isWatch = isWatch,
        sdk = Build.VERSION.SDK_INT,
    )
}

actual val CurrentPlatform: Platform
    get() = CurrentPlatformImpl
