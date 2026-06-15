package com.artemchep.keyguard.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.WindowDecoration
import java.awt.Dialog.ModalityType
import java.awt.Window

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
