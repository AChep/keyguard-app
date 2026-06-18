package com.artemchep.keyguard.desktop.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.recordLogDebug
import kotlinx.coroutines.delay
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Window
import kotlin.time.Duration.Companion.milliseconds

private const val WINDOW_FOCUS_RETRY_DELAY_MS = 50L
private const val WINDOW_FOCUS_RETRY_ATTEMPTS = 5
private const val WINDOW_VISIBLE_RETRY_DELAY_MS = 10L
private const val WINDOW_VISIBLE_RETRY_ATTEMPTS = 50

@Composable
internal fun WindowFocusRequestEffect(
    window: Window,
    visible: Boolean,
    requestKey: Any?,
    tag: String,
    requestId: Any? = requestKey,
    requestApplicationForeground: Boolean = true,
    onFocusAcquired: () -> Unit = {},
) {
    val updatedOnFocusAcquired by rememberUpdatedState(onFocusAcquired)

    LaunchedEffect(window, visible, requestKey, requestApplicationForeground) {
        if (!visible || requestKey == null) {
            return@LaunchedEffect
        }

        if (requestApplicationForeground) {
            requestAppForeground()
        }
        val windowVisible = window.awaitVisible(
            tag = tag,
            requestId = requestId,
        )
        if (!windowVisible) {
            return@LaunchedEffect
        }

        val focusAcquired = window.requestFocusWithRetry(
            tag = tag,
            requestId = requestId,
        )
        if (focusAcquired) {
            updatedOnFocusAcquired()
        }
    }
}

internal suspend fun Window.awaitVisible(
    tag: String,
    requestId: Any? = null,
    attempts: Int = WINDOW_VISIBLE_RETRY_ATTEMPTS,
    retryDelayMs: Long = WINDOW_VISIBLE_RETRY_DELAY_MS,
): Boolean {
    repeat(attempts) {
        if (isReadyForFocusOnEdt()) {
            return true
        }

        delay(retryDelayMs.milliseconds)
    }

    recordLogDebug {
        "${windowFocusLogPrefix(tag, requestId)} window did not become visible before focus timeout"
    }
    return false
}

internal suspend fun Window.requestFocusWithRetry(
    tag: String,
    requestId: Any? = null,
    attempts: Int = WINDOW_FOCUS_RETRY_ATTEMPTS,
    retryDelayMs: Long = WINDOW_FOCUS_RETRY_DELAY_MS,
    bringToFront: Boolean = CurrentPlatform !is Platform.Desktop.MacOS,
): Boolean {
    val prefix = windowFocusLogPrefix(tag, requestId)
    repeat(attempts) {
        requestFocusOnce(bringToFront)
        delay(retryDelayMs.milliseconds)
        if (hasKeyboardFocus()) {
            return true
        }
    }

    recordLogDebug {
        "$prefix window did not get keyboard focus after $attempts attempts"
    }
    return false
}

private fun windowFocusLogPrefix(
    tag: String,
    requestId: Any?,
) = buildString {
    append(tag)
    requestId?.let {
        append('[')
        append(it)
        append(']')
    }
}

private fun Window.requestFocusOnce(
    bringToFront: Boolean,
) = runOnEdt {
    if (!isVisible || !isDisplayable) {
        return@runOnEdt null
    }
    if (!focusableWindowState) {
        focusableWindowState = true
    }
    isAutoRequestFocus = true
    if (bringToFront) {
        toFront()
    }

    requestFocusInWindow()
    requestFocus()
    null
}

private fun Window.hasKeyboardFocus(
): Boolean = runOnEdt {
    val focusOwner = KeyboardFocusManager
        .getCurrentKeyboardFocusManager()
        .focusOwner
    focusOwner === this || isAncestorOf(focusOwner)
}

private fun Window.isReadyForFocusOnEdt(): Boolean = runOnEdt {
    isVisible && isDisplayable
}

private fun <T> runOnEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) {
        return block()
    }

    var result: Result<T>? = null
    EventQueue.invokeAndWait {
        result = runCatching(block)
    }
    return result!!.getOrThrow()
}
