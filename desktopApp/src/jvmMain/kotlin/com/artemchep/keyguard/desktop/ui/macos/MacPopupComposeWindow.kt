package com.artemchep.keyguard.desktop.ui.macos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.artemchep.keyguard.desktop.ui.initPopupComposeDialog
import java.awt.Dialog.ModalityType
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MacOwnerlessPopupComposeWindow(
    onCloseRequest: () -> Unit,
    state: WindowState,
    visible: Boolean,
    title: String,
    decoration: WindowDecoration,
    transparent: Boolean,
    resizable: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    focusRequestKey: Any?,
    alwaysOnTop: Boolean,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable DialogWindowScope.() -> Unit,
) {
    val coroutineContext = rememberCoroutineScope().coroutineContext
    val currentState = rememberUpdatedState(state)
    val currentOnCloseRequest = rememberUpdatedState(onCloseRequest)

    val overlaySessionActive by rememberMacPopupOverlaySession(visible)
    val popupVisible = visible && overlaySessionActive

    SwingDialog(
        visible = popupVisible,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        create = {
            ComposeDialog(
                owner = null as Window?,
                modalityType = ModalityType.MODELESS,
                coroutineContext = coroutineContext,
            ).apply {
                defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
                addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent?) {
                            currentOnCloseRequest.value()
                        }
                    },
                )
                addComponentListener(
                    object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent) {
                            currentState.value.size = DpSize(width.dp, height.dp)
                        }

                        override fun componentMoved(e: ComponentEvent) {
                            currentState.value.position = WindowPosition(x.dp, y.dp)
                        }
                    },
                )
                initPopupComposeDialog(
                    decoration = decoration,
                    transparent = transparent,
                    resizable = resizable,
                    enabled = enabled,
                    focusable = focusable,
                    alwaysOnTop = alwaysOnTop,
                    type = Window.Type.NORMAL,
                )
            }
        },
        dispose = ComposeDialog::dispose,
        update = { dialog ->
            dialog.title = title
            dialog.isResizable = resizable
            dialog.isEnabled = enabled
            dialog.focusableWindowState = focusable
            dialog.isAlwaysOnTop = alwaysOnTop
            dialog.modalityType = ModalityType.MODELESS
            dialog.applyMacOwnerlessPopupState(
                state = state,
            )
        },
    ) {
        MacPopupOverlayEffect(
            visible = popupVisible,
            focusRequestKey = focusRequestKey,
        )
        content()
    }
}
