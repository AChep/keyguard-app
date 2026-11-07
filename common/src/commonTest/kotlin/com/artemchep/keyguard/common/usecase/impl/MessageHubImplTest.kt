package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.platform.WindowId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageHubImplTest {
    @Test
    fun `a message with a window reaches that window even if it is not focused`() {
        val hub = MessageHubImpl()
        val main = mutableListOf<ToastMessage>()
        val popup = mutableListOf<ToastMessage>()
        hub.register(key = "App:Main", windowId = WindowId(1L), isFocused = { true }) { main += it }
        hub.register(key = "", windowId = WindowId(2L), isFocused = { false }) { popup += it }

        val message = ToastMessage(title = "Wrong password", windowId = WindowId(2L))
        hub.copy(message, target = "App:Main/unlock")

        assertEquals(listOf(message), popup)
        assertTrue(main.isEmpty())
    }

    @Test
    fun `a message without a window reaches only the focused window`() {
        val hub = MessageHubImpl()
        val unfocused = mutableListOf<ToastMessage>()
        val focused = mutableListOf<ToastMessage>()
        hub.register(key = "App:Main", windowId = WindowId(1L), isFocused = { false }) { unfocused += it }
        hub.register(key = "", windowId = WindowId(2L), isFocused = { true }) { focused += it }

        val message = ToastMessage(title = "Copied")
        hub.copy(message, target = "App:Main/vault")

        assertEquals(listOf(message), focused)
        assertTrue(unfocused.isEmpty())
    }

    @Test
    fun `a consumer registered without focus info receives messages without a window`() {
        val hub = MessageHubImpl()
        val messages = mutableListOf<ToastMessage>()
        hub.register(key = "macos", windowId = WindowId(0L)) { messages += it }

        val message = ToastMessage(title = "Synced")
        hub.copy(message, target = null)

        assertEquals(listOf(message), messages)
    }

    @Test
    fun `an unregistered consumer receives nothing`() {
        val hub = MessageHubImpl()
        val messages = mutableListOf<ToastMessage>()
        val unregister = hub.register(key = "", windowId = WindowId(1L)) { messages += it }
        unregister()

        hub.copy(ToastMessage(title = "Synced", windowId = WindowId(1L)), target = null)

        assertTrue(messages.isEmpty())
    }
}
