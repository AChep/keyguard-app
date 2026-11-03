package com.artemchep.keyguard.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.WindowDecoration
import com.artemchep.keyguard.platform.Platform
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeFalse

@OptIn(ExperimentalComposeUiApi::class)
class PopupComposeDialogTest {
    @Test
    fun `linux wayland approvals use normal windows in native and flatpak builds`() {
        listOf(false, true).forEach { isFlatpak ->
            assertEquals(
                Window.Type.NORMAL,
                resolveAgentApprovalWindowType(
                    platform = Platform.Desktop.Linux(isFlatpak = isFlatpak),
                    sessionType = "wayland",
                ),
            )
        }
    }

    @Test
    fun `other and unknown linux sessions keep popup windows`() {
        listOf("x11", "tty", "unspecified", "", null).forEach { sessionType ->
            assertEquals(
                Window.Type.POPUP,
                resolveAgentApprovalWindowType(
                    platform = Platform.Desktop.Linux.native,
                    sessionType = sessionType,
                ),
                "Session type: $sessionType",
            )
        }
    }

    @Test
    fun `wayland session hints do not affect other platforms`() {
        listOf(
            Platform.Desktop.Windows,
            Platform.Desktop.MacOS.Jvm,
            Platform.Desktop.Other,
        ).forEach { platform ->
            assertEquals(
                Window.Type.POPUP,
                resolveAgentApprovalWindowType(
                    platform = platform,
                    sessionType = "wayland",
                ),
                "Platform: $platform",
            )
        }
    }

    @Test
    fun `linux wayland approvals use a normal window and generic popups stay popups`() {
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
                        type = resolveAgentApprovalWindowType(
                            platform = Platform.Desktop.Linux.native,
                            sessionType = "wayland",
                        ),
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
