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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val POPUP_SESSION_ATTEMPTS = 10
private const val POPUP_SESSION_RETRY_DELAY_MS = 100L

/**
 * Owns the macOS popup overlay activation-policy session for as long as the
 * popup is [visible]. Returns whether the overlay session is currently held;
 * the dialog should only be shown once the session is active.
 */
@Composable
internal fun rememberMacPopupOverlaySession(
    visible: Boolean,
    requestKey: Any? = visible,
    beginSession: suspend () -> AutoCloseable? = macPopupOverlayManager::beginPopupSession,
): State<Boolean> {
    val currentVisible = rememberUpdatedState(visible)
    val overlaySessionState = remember {
        mutableStateOf<AutoCloseable?>(null)
    }
    val sessionActive = remember {
        derivedStateOf { overlaySessionState.value != null }
    }

    LaunchedEffect(visible, requestKey) {
        if (!visible) {
            overlaySessionState.value?.close()
            overlaySessionState.value = null
            return@LaunchedEffect
        }
        if (overlaySessionState.value != null) return@LaunchedEffect

        var acquiredSession: AutoCloseable? = null
        try {
            repeat(POPUP_SESSION_ATTEMPTS) { attempt ->
                acquiredSession = beginSession()
                // Native main-thread acquisition can finish after this effect is
                // cancelled. Leave ownership here so finally closes that session.
                currentCoroutineContext().ensureActive()
                val session = acquiredSession
                if (session != null) {
                    if (currentVisible.value) {
                        overlaySessionState.value = session
                        acquiredSession = null
                    }
                    if (attempt > 0) {
                        recordLogDebug {
                            "PopupComposeWindow acquired macOS popup overlay session after ${attempt + 1} attempts."
                        }
                    }
                    return@LaunchedEffect
                }
                if (attempt + 1 < POPUP_SESSION_ATTEMPTS) delay(POPUP_SESSION_RETRY_DELAY_MS)
            }
            recordLogDebug {
                "PopupComposeWindow failed to start macOS popup overlay session after $POPUP_SESSION_ATTEMPTS attempts."
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
