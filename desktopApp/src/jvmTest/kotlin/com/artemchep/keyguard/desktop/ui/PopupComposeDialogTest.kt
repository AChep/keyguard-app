package com.artemchep.keyguard.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.WindowDecoration
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeFalse

@OptIn(ExperimentalComposeUiApi::class)
class PopupComposeDialogTest {
    @Test
    fun `agent approvals use a normal window and generic popups stay popups`() {
        assumeFalse("Native window tests require a display", GraphicsEnvironment.isHeadless())
        var agent: ComposeDialog? = null
        var generic: ComposeDialog? = null
        try {
            EventQueue.invokeAndWait {
                agent = ComposeDialog().also { dialog ->
                    dialog.initPopupComposeDialog(
                        decoration = WindowDecoration.Undecorated(),
                        transparent = true,
                        resizable = false,
                        enabled = true,
                        focusable = true,
                        alwaysOnTop = true,
                        type = agentApprovalWindowType,
                    )
                }
                generic = ComposeDialog().also { dialog ->
                    dialog.initPopupComposeDialog(
                        decoration = WindowDecoration.Undecorated(),
                        transparent = true,
                        resizable = false,
                        enabled = true,
                        focusable = true,
                        alwaysOnTop = true,
                    )
                }
            }
            assertEquals(Window.Type.NORMAL, agent!!.type)
            assertEquals(Window.Type.POPUP, generic!!.type)
        } finally {
            EventQueue.invokeAndWait {
                agent?.dispose()
                generic?.dispose()
            }
        }
    }
}
