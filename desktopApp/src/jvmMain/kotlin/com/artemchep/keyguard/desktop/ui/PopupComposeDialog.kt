package com.artemchep.keyguard.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.WindowDecoration
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.awt.Dialog.ModalityType
import java.awt.Window

/**
 * Let compositors manage approval windows in Linux Wayland sessions, including
 * XWayland. Preserve popup behavior for other or unknown sessions.
 */
internal val agentApprovalWindowType: Window.Type by lazy {
    resolveAgentApprovalWindowType(
        platform = CurrentPlatform,
        sessionType = System.getenv("XDG_SESSION_TYPE"),
    )
}

internal fun resolveAgentApprovalWindowType(
    platform: Platform,
    sessionType: String?,
): Window.Type = if (platform is Platform.Desktop.Linux && sessionType == "wayland") {
    Window.Type.NORMAL
} else {
    Window.Type.POPUP
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun ComposeDialog.initPopupComposeDialog(
    decoration: WindowDecoration,
    transparent: Boolean,
    resizable: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    alwaysOnTop: Boolean,
    type: Window.Type = Window.Type.POPUP,
) {
    this.type = type
    isUndecorated = decoration != WindowDecoration.SystemDefault
    isTransparent = transparent
    isResizable = resizable
    isEnabled = enabled
    focusableWindowState = focusable
    isAlwaysOnTop = alwaysOnTop
    modalityType = ModalityType.MODELESS

    if (focusable) {
        isFocusable = true
        focusableWindowState = true
        isAutoRequestFocus = true
    }
}
