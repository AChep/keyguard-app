package com.artemchep.keyguard.desktop.ui

import com.artemchep.keyguard.platform.recordLogDebug
import kotlinx.coroutines.delay
import java.awt.EventQueue
import java.awt.Window

private const val WINDOW_FOCUS_RETRY_DELAY_MS = 50L
private const val WINDOW_FOCUS_RETRY_ATTEMPTS = 5
private const val WINDOW_VISIBLE_RETRY_DELAY_MS = 10L
private const val WINDOW_VISIBLE_RETRY_ATTEMPTS = 50

internal suspend fun Window.awaitVisible(
    tag: String,
    requestRevision: Int,
    attempts: Int = WINDOW_VISIBLE_RETRY_ATTEMPTS,
    retryDelayMs: Long = WINDOW_VISIBLE_RETRY_DELAY_MS,
): Boolean {
    repeat(attempts) { index ->
        if (isReadyForFocusOnEdt()) {
            recordLogDebug {
                "$tag[$requestRevision] window became visible before focus: attempt ${index + 1}/$attempts"
            }
            return true
        }

        delay(retryDelayMs)
    }

    recordLogDebug {
        "$tag[$requestRevision] window did not become visible before focus timeout"
    }
    return false
}

internal suspend fun Window.requestFocusWithRetry(
    tag: String,
    requestRevision: Int,
    attempts: Int = WINDOW_FOCUS_RETRY_ATTEMPTS,
    retryDelayMs: Long = WINDOW_FOCUS_RETRY_DELAY_MS,
): Boolean {
    recordLogDebug {
        "$tag[$requestRevision] window shown: ${snapshotWindowFocus().toLogString()}"
    }

    repeat(attempts) { index ->
        val attempt = index + 1
        val requestFocusInWindowAccepted = runOnEdt {
            if (!isVisible || !isDisplayable) {
                return@runOnEdt false
            }
            if (!focusableWindowState) {
                focusableWindowState = true
            }
            setAutoRequestFocus(true)
            toFront()
            val accepted = requestFocusInWindow()
            requestFocus()
            accepted
        }
        recordLogDebug {
            "$tag[$requestRevision] attempt $attempt/$attempts toFront issued"
        }
        recordLogDebug {
            "$tag[$requestRevision] attempt $attempt/$attempts focus request issued: " +
                "requestFocusInWindowAccepted=$requestFocusInWindowAccepted"
        }

        delay(retryDelayMs)

        val snapshot = snapshotWindowFocus()
        recordLogDebug {
            "$tag[$requestRevision] attempt $attempt/$attempts result: ${snapshot.toLogString()}"
        }
        if (snapshot.hasKeyboardFocus) {
            return true
        }
    }

    recordLogDebug {
        "$tag[$requestRevision] window did not get keyboard focus after retries"
    }
    return false
}

private data class WindowFocusSnapshot(
    val visible: Boolean,
    val displayable: Boolean,
    val focusableWindowState: Boolean,
    val autoRequestFocus: Boolean,
    val active: Boolean,
    val focused: Boolean,
    val focusOwner: String?,
) {
    val hasKeyboardFocus: Boolean
        get() = active || focused || focusOwner != null

    fun toLogString(): String = buildString {
        append("visible=").append(visible)
        append(", displayable=").append(displayable)
        append(", focusableWindowState=").append(focusableWindowState)
        append(", autoRequestFocus=").append(autoRequestFocus)
        append(", active=").append(active)
        append(", focused=").append(focused)
        append(", focusOwner=").append(focusOwner ?: "<none>")
    }
}

private fun Window.snapshotWindowFocus(): WindowFocusSnapshot = runOnEdt {
    WindowFocusSnapshot(
        visible = isVisible,
        displayable = isDisplayable,
        focusableWindowState = focusableWindowState,
        autoRequestFocus = isAutoRequestFocus,
        active = isActive,
        focused = isFocused,
        focusOwner = mostRecentFocusOwner
            ?.takeIf { it.hasFocus() }
            ?.javaClass
            ?.simpleName,
    )
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
