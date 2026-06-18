package com.artemchep.keyguard.desktop.util

import java.awt.Desktop

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
