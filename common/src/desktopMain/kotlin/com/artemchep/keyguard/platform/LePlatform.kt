package com.artemchep.keyguard.platform

import org.apache.commons.lang3.SystemUtils

private val isFlatpak: Boolean
    get() = System.getenv("container") == "flatpak"

actual val CurrentPlatform: Platform by lazy {
    when {
        SystemUtils.IS_OS_WINDOWS ->
            Platform.Desktop.Windows

        SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_MAC_OSX ->
            Platform.Desktop.MacOS.Jvm

        SystemUtils.IS_OS_LINUX ->
            Platform.Desktop.Linux(
                isFlatpak = isFlatpak,
            )

        else -> Platform.Desktop.Other
    }
}
