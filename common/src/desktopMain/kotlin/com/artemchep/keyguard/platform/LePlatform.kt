package com.artemchep.keyguard.platform

import org.apache.commons.lang3.SystemUtils
import java.io.File

/**
 * Mirrors the detection in the native `linux_shared.rs` and the
 * `linux_rescan.rs` of the authenticator fork in thirdParty/rust; keep all
 * of them in sync.
 */
private val isFlatpak: Boolean
    get() = System.getenv("container") == "flatpak" ||
        File("/.flatpak-info").exists()

/** The installed app ID, which differs for repackaged or branch builds. */
internal fun flatpakId(): String =
    System.getenv("FLATPAK_ID")
        ?.takeIf { it.isNotBlank() }
        ?: FLATPAK_APP_ID_FALLBACK

private const val FLATPAK_APP_ID_FALLBACK = "com.artemchep.keyguard"

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
