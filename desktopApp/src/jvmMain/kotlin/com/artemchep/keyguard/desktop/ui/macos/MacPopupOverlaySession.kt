package com.artemchep.keyguard.desktop.ui.macos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.jna.macos.macPopupOverlayManager
import com.artemchep.keyguard.platform.recordLogDebug

/**
 * Owns the macOS popup overlay activation-policy session for as long as the
 * popup is [visible]. Returns whether the overlay session is currently held;
 * the dialog should only be shown once the session is active.
 */
@Composable
internal fun rememberMacPopupOverlaySession(
    visible: Boolean,
): State<Boolean> {
    val currentVisible = rememberUpdatedState(visible)
    val overlaySessionState = remember {
        mutableStateOf<AutoCloseable?>(null)
    }
    val sessionActive = remember {
        derivedStateOf { overlaySessionState.value != null }
    }

    LaunchedEffect(visible) {
        if (!visible) {
            overlaySessionState.value?.close()
            overlaySessionState.value = null
            return@LaunchedEffect
        }

        var acquiredSession: AutoCloseable? = null
        try {
            acquiredSession = macPopupOverlayManager.beginPopupSession()
            val session = acquiredSession
            if (session == null) {
                recordLogDebug {
                    "PopupComposeWindow failed to start macOS popup overlay session."
                }
                return@LaunchedEffect
            }

            if (currentVisible.value) {
                overlaySessionState.value?.close()
                overlaySessionState.value = session
                acquiredSession = null
            }
        } finally {
            acquiredSession?.close()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            overlaySessionState.value?.close()
            overlaySessionState.value = null
        }
    }

    return sessionActive
}
