package com.artemchep.keyguard.desktop.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

/**
 * A composable effect that listens for app reopened events.
 */
@Composable
fun AppReopenedListenerEffect(
    onAppReopened: () -> Unit,
) {
    DisposableEffect(Unit) {
        val desktop = Desktop.getDesktop()
        var listener: AppReopenedListener? = null

        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            listener = AppReopenedListener { onAppReopened() }
            desktop.addAppEventListener(listener)
        }

        onDispose {
            listener?.let { desktop.removeAppEventListener(it) }
        }
    }
}
