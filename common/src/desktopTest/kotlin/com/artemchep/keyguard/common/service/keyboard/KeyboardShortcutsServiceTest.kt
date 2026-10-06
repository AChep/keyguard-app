package com.artemchep.keyguard.common.service.keyboard

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class KeyboardShortcutsServiceTest {
    private val copy = KeyEvent(Key.C, KeyEventType.KeyDown, isCtrlPressed = true)

    @Test
    fun `copy shortcuts target only their window regardless of registration order`() {
        for (reverseOrder in listOf(false, true)) {
            val service = KeyboardShortcutsServiceImpl(SequentialUuidCryptoGenerator())
            val mainWindow = WindowId.create()
            val popupWindow = WindowId.create()
            val handled = mutableListOf<WindowId>()
            val windows = listOf(mainWindow, popupWindow)
            (if (reverseOrder) windows.reversed() else windows).forEach { windowId ->
                service.register(windowId) { event ->
                    assertEquals(copy, event)
                    handled += windowId
                    true
                }
            }

            assertTrue(service.handle(popupWindow, copy))
            assertEquals(listOf(popupWindow), handled)
            assertTrue(service.handle(mainWindow, copy))
            assertEquals(listOf(popupWindow, mainWindow), handled)
        }
    }

    @Test
    fun `an unhandled popup shortcut never falls through to a background window`() {
        val service = KeyboardShortcutsServiceImpl(SequentialUuidCryptoGenerator())
        val mainWindow = WindowId.create()
        val popupWindow = WindowId.create()
        var mainCalls = 0
        var popupCalls = 0
        service.register(mainWindow) {
            mainCalls++
            true
        }

        // The popup may have no router yet, or a router with no matching action.
        assertFalse(service.handle(popupWindow, copy))
        service.register(popupWindow) {
            popupCalls++
            false
        }
        assertFalse(service.handle(popupWindow, copy))
        assertEquals(0, mainCalls)
        assertEquals(1, popupCalls)
    }

    @Test
    fun `disposing a router preserves other routers and windows`() {
        val service = KeyboardShortcutsServiceImpl(SequentialUuidCryptoGenerator())
        val mainWindow = WindowId.create()
        val popupWindow = WindowId.create()
        val unregisterMain = service.register(mainWindow) { true }
        val unregisterPopup = service.register(popupWindow) { true }
        val unregisterPopupSibling = service.register(popupWindow) { true }

        unregisterPopup()
        unregisterPopup()
        assertTrue(service.handle(popupWindow, copy))
        assertTrue(service.handle(mainWindow, copy))

        unregisterPopupSibling()
        assertFalse(service.handle(popupWindow, copy))
        assertTrue(service.handle(mainWindow, copy))

        unregisterMain()
        assertFalse(service.handle(mainWindow, copy))
    }

    private class SequentialUuidCryptoGenerator : TestCryptoGenerator() {
        private var nextId = 0

        override fun uuid(): String = (++nextId).toString()
    }
}
