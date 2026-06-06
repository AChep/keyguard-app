package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.desktop.ui.macos.MacOwnerlessPopupComposeWindow
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalWindowRev
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.WindowRev
import java.awt.Dialog.ModalityType

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PopupComposeWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    icon: Painter? = null,
    decoration: WindowDecoration = WindowDecoration.SystemDefault,
    transparent: Boolean = false,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    focusRequestKey: Any? = visible,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    if (CurrentPlatform is Platform.Desktop.MacOS) {
        MacOwnerlessPopupComposeWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            decoration = decoration,
            transparent = transparent,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            focusRequestKey = focusRequestKey,
            alwaysOnTop = alwaysOnTop,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            content = content,
        )
        return
    }

    val dialogState = remember(state) {
        WindowBackedDialogState(state)
    }

    SwingDialog(
        onCloseRequest = onCloseRequest,
        state = dialogState,
        visible = visible,
        title = title,
        icon = icon,
        decoration = decoration,
        transparent = transparent,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        modalityType = ModalityType.MODELESS,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        init = { dialog ->
            dialog.initPopupComposeDialog(
                decoration = decoration,
                transparent = transparent,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
            )
        },
    ) {
        val windowRev = remember(focusRequestKey) {
            WindowRev.generateWindowRev()
        }
        CompositionLocalProvider(
            LocalWindowRev provides windowRev,
        ) {
            content()
        }
    }
}

private class WindowBackedDialogState(
    private val state: WindowState,
) : DialogState {
    override var position: WindowPosition
        get() = state.position
        set(value) {
            state.position = value
        }

    override var size: DpSize
        get() = state.size
        set(value) {
            state.size = value
        }
}
