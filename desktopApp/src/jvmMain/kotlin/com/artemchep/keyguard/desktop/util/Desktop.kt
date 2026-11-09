package com.artemchep.keyguard.desktop.util

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.awt.Desktop
import java.io.IOException
import java.net.URI

fun getAwtDesktopOrNull(
    action: Desktop.Action? = null,
): Desktop? {
    if (!Desktop.isDesktopSupported()) {
        return null
    }
    val desktop = Desktop.getDesktop()
    if (action != null && !desktop.isSupported(action)) {
        return null
    }

    return desktop
}

/**
 * Opens the [uri] with the AWT [action], or with the platform's
 * launcher if AWT does not support the action or fails to run it.
 *
 * On Linux, AWT supports [Desktop.Action.BROWSE] and [Desktop.Action.MAIL]
 * only if GIO has gvfs. The Flatpak runtime has no gvfs, so there
 * `xdg-open` opens the URI through the OpenURI portal.
 */
fun openUri(
    uri: URI,
    action: Desktop.Action,
    block: Desktop.(URI) -> Unit,
) {
    val desktop = getAwtDesktopOrNull(action)
    val error = if (desktop != null) {
        try {
            desktop.block(uri)
            return
        } catch (e: IOException) {
            e
        }
    } else {
        UnsupportedOperationException(
            "The ${action.name} action is not supported on the current platform!",
        )
    }

    val handled = openUriWithLauncher(uri.toString())
    if (!handled) {
        throw error
    }
}

private fun openUriWithLauncher(
    uri: String,
): Boolean {
    val command = when (CurrentPlatform) {
        is Platform.Desktop.Windows -> arrayOf("rundll32", "url.dll,FileProtocolHandler", uri)
        is Platform.Desktop.MacOS -> arrayOf("open", uri)
        is Platform.Desktop.Linux -> arrayOf("xdg-open", uri)
        // Not supported.
        else -> return false
    }
    Runtime.getRuntime().exec(command)
    return true
}
