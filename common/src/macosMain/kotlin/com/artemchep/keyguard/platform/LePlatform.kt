package com.artemchep.keyguard.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

@OptIn(ExperimentalForeignApi::class)
actual val CurrentPlatform: Platform by lazy {
    Platform.Desktop.MacOS.Native(
        majorVersion = NSProcessInfo.processInfo.operatingSystemVersion
            .useContents { majorVersion.toInt() },
    )
}
