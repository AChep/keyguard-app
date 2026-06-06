package com.artemchep.keyguard.desktop.util

import java.awt.Desktop

internal fun requestAppForeground(
): Boolean {
    val desktop = getAwtDesktopOrNull(
        action = Desktop.Action.APP_REQUEST_FOREGROUND,
    ) ?: return false
    return runCatching {
        desktop.requestForeground(false)
        true
    }.getOrElse {
        false
    }
}
