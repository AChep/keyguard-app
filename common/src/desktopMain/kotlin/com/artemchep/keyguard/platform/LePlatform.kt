package com.artemchep.keyguard.platform

import org.apache.commons.lang3.SystemUtils
import java.io.File

/**
 * Mirrors the detection in the native `linux_shared.rs`; keep both in sync.
 */
private val isFlatpak: Boolean
    get() = System.getenv("container") == "flatpak" ||
        File("/.flatpak-info").exists()

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
