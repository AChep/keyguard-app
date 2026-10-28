package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_flatpak_device_access
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.io.File

/** Restores the device access that the Flatpak manifest grants. */
private fun flatpakDeviceAccessCommand(appId: String) =
    "flatpak override --user --device=all $appId"

/**
 * Throws the error to show instead of a security key prompt if Keyguard
 * cannot reach the key's HID device. Call it right before the native client.
 */
internal suspend fun requireSecurityKeyAccess(
    title: StringResource,
    platform: Platform = CurrentPlatform,
    flatpakInfo: File = File(FLATPAK_INFO_PATH),
    appId: String = flatpakId(),
) {
    // Only the sandbox can hide the device.
    if (platform !is Platform.Desktop.Linux || !platform.isFlatpak) return
    if (withContext(Dispatchers.IO) { hasSecurityKeyAccess(flatpakInfo) }) return
    val message = getString(Res.string.error_flatpak_device_access, flatpakDeviceAccessCommand(appId))
    throw SecurityKeyAccessException(title, message)
}

/**
 * Inside Flatpak only `--device=all` exposes /dev/hidraw. A user override can
 * revoke it, and then the native client waits for a key it can never see.
 */
internal fun hasSecurityKeyAccess(flatpakInfo: File): Boolean {
    // Let the native client try if the sandbox metadata is unreadable.
    val info = runCatching { flatpakInfo.readText() }.getOrNull()
    return info == null || flatpakInfoGrantsAllDevices(info)
}

/**
 * Flatpak writes the final permissions, overrides included, to the `[Context]`
 * group of /.flatpak-info and leaves out revoked ones.
 */
internal fun flatpakInfoGrantsAllDevices(info: String): Boolean =
    flatpakInfoValue(info, "Context", "devices")?.split(';')?.any { it.trim() == "all" } == true

private class SecurityKeyAccessException(
    title: StringResource,
    message: String,
) : RuntimeException(message), Readable {
    override val title = TextHolder.Res(title)
    override val text = TextHolder.Value(message)
}
