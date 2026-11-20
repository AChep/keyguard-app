package com.artemchep.keyguard.feature.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds

private val HOLD_BACK = 1.seconds

/**
 * Holds the buttons of an approval prompt back until its window has had
 * focus with no input for a second, so keys and clicks meant for another
 * app can't answer it.
 *
 * Input while held back restarts the wait. Input after the buttons arm
 * does nothing, so a deliberate Enter still works. A press that starts
 * while held back holds them back until its release, so it can't click
 * once they arm. Losing window focus holds them back again.
 */
@Stable
class AgentApprovalInputGuard {
    /** `true` once the buttons may answer the request. */
    var armed by mutableStateOf(false)
        private set

    private val holds = MutableStateFlow(0)

    /** `true` while a press that started held back is down. */
    private val pressing = MutableStateFlow(false)

    /** Reports a key press, click or tap. Restarts the wait while held back. */
    fun onInput() {
        if (!armed) {
            holds.update { it + 1 }
        }
    }

    /**
     * Reports a pointer going down. While held back, the buttons stay
     * held back until [onRelease]; a press after they arm does nothing.
     */
    fun onPress() {
        if (!armed) {
            pressing.value = true
            holds.update { it + 1 }
        }
    }

    /** Reports that all pointers went up. Ends the press of [onPress], which restarts the wait. */
    fun onRelease() {
        pressing.value = false
    }

    /** Holds the buttons back and restarts the wait, for example for a new request. */
    fun restart() {
        armed = false
        holds.update { it + 1 }
    }

    /**
     * Arms the buttons after a second of quiet while the window is
     * [focused]. Runs until cancelled; relaunch it when the focus changes.
     */
    suspend fun run(focused: Boolean) {
        armed = false
        if (!focused) {
            // The release of a press may never come while unfocused.
            pressing.value = false
            return
        }
        combine(holds, pressing) { _, pressed -> pressed }.collectLatest { pressed ->
            armed = false
            if (!pressed) {
                delay(HOLD_BACK)
                armed = true
            }
        }
    }
}

/** The guard of the approval prompt, see [ProvideAgentApprovalInputGuard]. */
val LocalAgentApprovalInputGuard = compositionLocalOf<AgentApprovalInputGuard> {
    error("An approval prompt must be hosted in ProvideAgentApprovalInputGuard.")
}

/**
 * Runs the [guard] on the focus of the current window and provides it to
 * the [content]. A new [requestId] restarts the wait, so a request, or its
 * unlock screen, never inherits the armed state of the one before.
 */
@Composable
fun ProvideAgentApprovalInputGuard(
    guard: AgentApprovalInputGuard,
    requestId: String?,
    content: @Composable () -> Unit,
) {
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(guard, focused) {
        guard.run(focused)
    }
    // Runs when the composition applies, before the next frame.
    DisposableEffect(guard, requestId) {
        if (requestId != null) guard.restart()
        onDispose {}
    }
    CompositionLocalProvider(
        LocalAgentApprovalInputGuard provides guard,
        content = content,
    )
}

/**
 * Whether the caller's buttons may answer the request. Restarts the wait
 * when the caller enters the composition, and is `false` until then, so a
 * guard armed earlier never arms fresh buttons, not even for one frame.
 */
@Composable
internal fun rememberAgentApprovalArmed(): Boolean {
    val guard = LocalAgentApprovalInputGuard.current
    var restarted by remember(guard) {
        mutableStateOf(false)
    }
    // Runs when the composition applies, before the next frame.
    DisposableEffect(guard) {
        guard.restart()
        restarted = true
        onDispose {}
    }
    return restarted && guard.armed
}

/**
 * Reports every press and release inside the element to the [guard].
 * Sees them before its children and never consumes them.
 */
fun Modifier.agentApprovalInputGuard(
    guard: AgentApprovalInputGuard,
): Modifier = pointerInput(guard) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when {
                event.type == PointerEventType.Press -> guard.onPress()
                event.type == PointerEventType.Release &&
                    event.changes.none { it.pressed } -> guard.onRelease()
            }
        }
    }
}
