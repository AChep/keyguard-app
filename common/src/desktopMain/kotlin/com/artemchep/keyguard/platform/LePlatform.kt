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
        File(FLATPAK_INFO_PATH).exists()

/** The installed app ID, which differs for repackaged or branch builds. */
internal fun flatpakId(): String =
    System.getenv("FLATPAK_ID")
        ?.takeIf { it.isNotBlank() }
        ?: FLATPAK_APP_ID_FALLBACK

private const val FLATPAK_APP_ID_FALLBACK = "com.artemchep.keyguard"

/** The sandbox metadata that Flatpak writes for the running instance. */
internal const val FLATPAK_INFO_PATH = "/.flatpak-info"

/** Reads [key] of [group] from the contents of [FLATPAK_INFO_PATH]. */
internal fun flatpakInfoValue(info: String, group: String, key: String): String? {
    var current: String? = null
    info.lineSequence().map(String::trim).forEach { line ->
        if (line.startsWith("[")) {
            current = line
        } else if (current == "[$group]" && line.substringBefore('=').trim() == key) {
            return line.substringAfter('=').trim()
        }
    }
    return null
}

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
