package com.artemchep.keyguard.feature.navigation

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class NavigationKeyboardBackTest : NavigationBackTestFixture() {
    @Test
    fun `Escape targets the deepest poppable router regardless of registration order`() {
        for (reverseOrder in listOf(false, true)) {
            val handler = BackHandler()
            val home = entry("home")
            val sendList = entry("send-list")
            val sendDetail = entry("send-detail")
            val parent = controller()
            val child = controller()
            val parentPile = pile(home, sendList)
            val childPile = pile(sendList, sendDetail)
            val registrations = listOf(
                { handler.register(parent, listOf(home, sendList), parentPile::canPop) },
                { handler.register(child, listOf(home, sendList, sendDetail), childPile::canPop) },
            )
            (if (reverseOrder) registrations.reversed() else registrations).forEach { it() }

            assertTrue(handler.handleKeyEvent(escape))
            assertEquals(emptyList(), parent.intents)
            assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), child.intents)
        }
    }

    @Test
    fun `a child at its local root lets the parent router handle Back`() {
        val handler = BackHandler()
        val home = entry("home")
        val settings = entry("settings")
        val settingsList = entry("settings-list")
        val parentPile = pile(home, settings)
        val childPile = pile(settingsList)
        val parent = controller()
        val child = controller()
        handler.register(parent, listOf(home, settings), parentPile::canPop)
        handler.register(child, listOf(home, settings, settingsList), childPile::canPop)

        assertTrue(handler.handleKeyEvent(escape))
        assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), parent.intents)
        assertEquals(emptyList(), child.intents)
    }

    @Test
    fun `root and empty handlers do not dispatch an application exit`() {
        val handler = BackHandler()
        assertFalse(handler.handleKeyEvent(escape))

        val root = entry("root")
        val rootPile = pile(root)
        val controller = controller()
        handler.register(controller, emptyList())
        handler.register(controller, listOf(root), rootPile::canPop)

        assertFalse(handler.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `live stack guard prevents repeated Escape from popping past the root`() {
        val handler = BackHandler()
        val root = entry("root")
        val detail = entry("detail")
        val pile = pile(root, detail)
        val controller = controller {
            pile.value.last().value = listOf(root).toPersistentList()
        }
        handler.register(controller, listOf(root, detail), pile::canPop)

        assertTrue(handler.handleKeyEvent(escape))
        // No re-registration or flow collection between the two key events.
        assertFalse(handler.handleKeyEvent(escape))
        assertFalse(pile.canPop())
        assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), controller.intents)
    }

    @Test
    fun `Escape ignores key up modifiers and other keys`() {
        val handler = BackHandler()
        val controller = controller()
        handler.register(controller, listOf(entry("detail"))) { true }
        val ignored = listOf(
            KeyEvent(Key.Escape, KeyEventType.KeyUp),
            KeyEvent(Key.Escape, KeyEventType.KeyDown, isCtrlPressed = true),
            KeyEvent(Key.Escape, KeyEventType.KeyDown, isMetaPressed = true),
            KeyEvent(Key.Escape, KeyEventType.KeyDown, isAltPressed = true),
            KeyEvent(Key.Escape, KeyEventType.KeyDown, isShiftPressed = true),
            KeyEvent(Key.Backspace, KeyEventType.KeyDown),
            KeyEvent(Key.Delete, KeyEventType.KeyDown),
            KeyEvent(Key.Enter, KeyEventType.KeyDown),
        )

        ignored.forEach { assertFalse(handler.handleKeyEvent(it)) }
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `registrations are isolated by window and removed on disposal`() {
        val mainWindow = BackHandler()
        val popupWindow = BackHandler()
        val controller = controller()
        val unregister = mainWindow.register(controller, listOf(entry("detail"))) { true }

        assertFalse(popupWindow.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
        unregister()
        assertFalse(mainWindow.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `destroyed routes cannot receive Escape during their exit transition`() {
        val handler = BackHandler()
        val controller = controller()
        val root = entry("root")
        val detail = entry("detail")
        val pile = pile(root, detail)
        handler.register(controller, listOf(root, detail), pile::canPop)
        assertTrue(pile.canPop())
        detail.destroy()

        assertFalse(handler.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `an unregistered outgoing router no longer shadows the active router`() {
        val handler = BackHandler()
        val root = entry("root")
        val active = controller()
        val outgoing = controller()
        handler.register(active, listOf(root, entry("active"))) { true }
        val unregisterOutgoing =
            handler.register(outgoing, listOf(root, entry("old-parent"), entry("old-detail"))) { true }
        unregisterOutgoing()

        assertTrue(handler.handleKeyEvent(escape))
        assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), active.intents)
        assertEquals(emptyList(), outgoing.intents)
    }
}
