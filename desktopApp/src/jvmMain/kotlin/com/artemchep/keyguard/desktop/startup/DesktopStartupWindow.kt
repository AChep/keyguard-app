package com.artemchep.keyguard.desktop.startup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.presentation.startup.DesktopLaunchReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a background start waits for a tray host before it shows the main window. */
internal val TRAY_HOST_GRACE = 10.seconds

private enum class TrayHostEvent {
    AVAILABLE,
    ABSENT,
    UNKNOWN,
    GRACE_ENDED,
}

/**
 * Returns once a hidden app needs its window: no tray host within [grace], or the
 * host was lost after it appeared. `null` availability means not known yet.
 */
internal suspend fun Flow<Boolean?>.awaitTrayHostAbsence(grace: Duration = TRAY_HOST_GRACE) {
    var hostSeen = false
    merge(
        map { available ->
            when (available) {
                true -> TrayHostEvent.AVAILABLE
                false -> TrayHostEvent.ABSENT
                null -> TrayHostEvent.UNKNOWN
            }
        },
        flow {
            delay(grace)
            emit(TrayHostEvent.GRACE_ENDED)
        },
    ).firstOrNull { event ->
        when (event) {
            TrayHostEvent.AVAILABLE -> {
                hostSeen = true
                false
            }
            // A host may still appear within the grace period.
            TrayHostEvent.ABSENT -> hostSeen
            TrayHostEvent.UNKNOWN -> false
            TrayHostEvent.GRACE_ENDED -> !hostSeen
        }
    }
}

@Stable
internal class DesktopStartupWindow(initiallyVisible: Boolean) {
    var created by mutableStateOf(initiallyVisible)
        private set
    var visible by mutableStateOf(initiallyVisible)
        private set

    fun open() {
        created = true
        visible = true
    }

    fun close(closeToTray: Boolean, onExit: () -> Unit) {
        if (closeToTray) visible = false else onExit()
    }
}

@Composable
internal fun rememberDesktopStartupWindow(
    reason: DesktopLaunchReason,
    startInBackground: Flow<Boolean>,
    closeToTray: Boolean,
    /** Whether a tray host is available, `null` until known. */
    trayHost: Flow<Boolean?>,
    trayHostGrace: Duration = TRAY_HOST_GRACE,
): DesktopStartupWindow {
    val state = remember { DesktopStartupWindow(reason.showsMainWindow(null) == true) }
    val currentTrayHost by rememberUpdatedState(trayHost)
    LaunchedEffect(reason, startInBackground) {
        val startInBackgroundValue = if (reason == DesktopLaunchReason.LOGIN) startInBackground.first() else null
        if (reason.showsMainWindow(startInBackgroundValue) == true) {
            state.open()
        } else if (reason.keepsProcessAlive) {
            // No tray host, or one lost before the first open: show the window, or the app is invisible.
            currentTrayHost.awaitTrayHostAbsence(trayHostGrace)
            if (!state.created) state.open()
        }
    }
    LaunchedEffect(closeToTray) {
        // Turning off the tray must not construct an unlock screen during a background start.
        if (!closeToTray && state.created) state.open()
    }
    return state
}

/** Keep this gate outside Window: visible=false still composes the main content. */
@Composable
internal fun DesktopStartupWindowHost(
    state: DesktopStartupWindow,
    closeToTray: Boolean,
    onExit: () -> Unit,
    window: @Composable (visible: Boolean, onClose: () -> Unit) -> Unit,
) {
    if (state.created) window(state.visible) { state.close(closeToTray, onExit) }
}
