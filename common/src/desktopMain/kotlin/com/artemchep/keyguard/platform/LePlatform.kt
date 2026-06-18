package com.artemchep.keyguard.platform

import org.apache.commons.lang3.SystemUtils

// The 'container' environment variable is set by 'flatpak run'. This
// is enough as long as the app is launched directly by Flatpak; we
// might need to check for the /.flatpak-info file if env is not enough
// for some reason.
//
// This must be synced with the Rust code.
private val isFlatpak: Boolean
    get() = System.getenv("container") == "flatpak"

actual val CurrentPlatform: Platform by lazy {
    when {
        SystemUtils.IS_OS_WINDOWS ->
            Platform.Desktop.Windows

        SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_MAC_OSX ->
            Platform.Desktop.MacOS

        SystemUtils.IS_OS_LINUX ->
            Platform.Desktop.Linux(
                isFlatpak = isFlatpak,
            )

        else -> Platform.Desktop.Other
    }
}
