package com.artemchep.keyguard.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Window

val LocalComposeWindow = staticCompositionLocalOf<Window> {
    val msg = "LocalComposeWindow is not provided."
    throw IllegalStateException(msg)
}

/**
 * Native handle of the window, `null` if the
 * window type does not expose one.
 */
val Window.nativeWindowHandle: Long?
    get() = when (this) {
        is ComposeWindow -> windowHandle
        is ComposeDialog -> windowHandle
        else -> null
    }
