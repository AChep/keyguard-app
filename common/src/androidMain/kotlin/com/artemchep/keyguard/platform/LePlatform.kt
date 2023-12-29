package com.artemchep.keyguard.platform

import android.os.Build

private val platform by lazy {
    val isChromebook = Build.DEVICE.orEmpty()
        .matches(".+_cheets|cheets_.+".toRegex())
    Platform.Mobile.Android(
        isChromebook = isChromebook,
    )
}

actual val CurrentPlatform: Platform
    get() = platform
