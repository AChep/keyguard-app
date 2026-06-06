package com.artemchep.keyguard.desktop.ui.macos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.DialogWindowScope
import com.artemchep.jna.macos.macPopupOverlayManager
import com.artemchep.keyguard.desktop.util.requestAppForeground
import com.artemchep.keyguard.desktop.util.requestFocusWithRetry
import com.artemchep.keyguard.platform.recordLogDebug
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun DialogWindowScope.MacPopupOverlayEffect(
    visible: Boolean,
    focusRequestKey: Any?,
) {
    LaunchedEffect(window, visible, focusRequestKey) {
        if (!visible) {
            return@LaunchedEffect
        }

        // Wait till the native window handle becomes
        // available, skip if we failed to obtain it.
        val windowHandle = window.awaitMacPopupWindowHandle()
            .takeUnless { it == 0L }
            ?: return@LaunchedEffect

        val applied = macPopupOverlayManager.applyOverlayAndWait(
            windowHandle = windowHandle,
            makeKeyWindow = true,
        )
        if (applied == null) {
            recordLogDebug {
                "[MacPopupOverlayEffect] failed to apply macOS popup overlay."
            }
            return@LaunchedEffect
        }

        if (!applied) {
            recordLogDebug {
                "[MacPopupOverlayEffect] applied macOS popup overlay, but the window could not become key."
            }
        }

        val focusRequestId = windowHandle.toString(16)
        requestAppForeground()
        delay(50L.milliseconds)
        window.requestFocusWithRetry(
            tag = "MacPopupOverlay",
            requestId = focusRequestId,
            attempts = 5,
            bringToFront = true,
        )
    }
}

private suspend fun ComposeDialog.awaitMacPopupWindowHandle(): Long {
    repeat(10) {
        val nativeWindowHandle = nativeWindowHandleOrZeroOnEdt()
        if (nativeWindowHandle != 0L) {
            return nativeWindowHandle
        }

        delay(10L.milliseconds)
    }
    return 0L
}

private suspend fun ComposeDialog.nativeWindowHandleOrZeroOnEdt(): Long =
    runOnEdt {
        if (isDisplayable && isVisible) {
            windowHandle
        } else {
            0L
        }
    }

private suspend fun <T> runOnEdt(
    block: () -> T,
): T {
    if (EventQueue.isDispatchThread()) {
        return block()
    }

    return suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val result = runCatching(block)
            if (continuation.isActive) {
                continuation.resumeWith(result)
            }
        }
    }
}
