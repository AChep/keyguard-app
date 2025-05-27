package com.artemchep.keyguard.platform

import android.os.Build

val CurrentPlatformImpl by lazy {
    val isChromebook = Build.DEVICE.orEmpty()
        .matches(".+_cheets|cheets_.+".toRegex())
    Platform.Mobile.Android(
        isChromebook = isChromebook,
        sdk = Build.VERSION.SDK_INT,
    )
}

actual val CurrentPlatform: Platform
    get() = CurrentPlatformImpl
