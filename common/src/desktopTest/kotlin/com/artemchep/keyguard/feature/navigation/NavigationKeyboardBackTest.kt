package com.artemchep.keyguard.feature.navigation

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class NavigationKeyboardBackTest : NavigationBackTestFixture() {
    @Test
    fun `Escape targets the deepest poppable router regardless of registration order`() {
        for (reverseOrder in listOf(false, true)) {
            val host = NavigationHost()
            val parentPile = pile("home", "send-list")
            val childPile = pile("send-list", "send-detail")
            val home = parentPile.entry("home")
            val sendList = parentPile.entry("send-list")
            val sendDetail = childPile.entry("send-detail")
            val parent = controller()
            val child = controller()
            val registrations = listOf(
                { host.registerRouter(parent, listOf(home, sendList), parentPile::canPop) },
                { host.registerRouter(child, listOf(home, sendList, sendDetail), childPile::canPop) },
            )
            (if (reverseOrder) registrations.reversed() else registrations).forEach { it() }

            assertTrue(host.handleKeyEvent(escape))
            assertEquals(emptyList(), parent.intents)
            assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), child.intents)
        }
    }

    @Test
    fun `a child at its local root lets the parent router handle Back`() {
        val host = NavigationHost()
        val parentPile = pile("home", "settings")
        val childPile = pile("settings-list")
        val home = parentPile.entry("home")
        val settings = parentPile.entry("settings")
        val settingsList = childPile.entry("settings-list")
        val parent = controller()
        val child = controller()
        host.registerRouter(parent, listOf(home, settings), parentPile::canPop)
        host.registerRouter(child, listOf(home, settings, settingsList), childPile::canPop)

        assertTrue(host.handleKeyEvent(escape))
        assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), parent.intents)
        assertEquals(emptyList(), child.intents)
    }

    @Test
    fun `root and empty handlers do not dispatch an application exit`() {
        val host = NavigationHost()
        assertFalse(host.handleKeyEvent(escape))

        val rootPile = pile("root")
        val root = rootPile.entry("root")
        val controller = controller()
        host.registerRouter(controller, emptyList()) { false }
        host.registerRouter(controller, listOf(root), rootPile::canPop)

        assertFalse(host.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `live stack guard prevents repeated Escape from popping past the root`() {
        val host = NavigationHost()
        val pile = pile("root", "detail")
        val root = pile.entry("root")
        val detail = pile.entry("detail")
        val controller = controller {
            assertNull(pile.dispatch(NavigationIntent.Pop))
        }
        host.registerRouter(controller, listOf(root, detail), pile::canPop)

        assertTrue(host.handleKeyEvent(escape))
        // No re-registration or invalidation between the two key events.
        assertFalse(host.handleKeyEvent(escape))
        assertFalse(pile.canPop())
        assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), controller.intents)
    }

    @Test
    fun `Escape ignores key up modifiers and other keys`() {
        val host = NavigationHost()
        val controller = controller()
        host.registerRouter(controller, listOf(entry("detail"))) { true }
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

        ignored.forEach { assertFalse(host.handleKeyEvent(it)) }
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `registrations are isolated by window and removed on disposal`() {
        val mainWindow = NavigationHost()
        val popupWindow = NavigationHost()
        val controller = controller()
        val unregister = mainWindow.registerRouter(controller, listOf(entry("detail"))) { true }

        assertFalse(popupWindow.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
        unregister()
        assertFalse(mainWindow.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `destroyed routes cannot receive Escape during their exit transition`() {
        val host = NavigationHost()
        val controller = controller()
        val pile = pile("root", "detail")
        val root = pile.entry("root")
        val detail = pile.entry("detail")
        host.registerRouter(controller, listOf(root, detail), pile::canPop)
        assertTrue(pile.canPop())
        detail.destroy()

        assertFalse(host.handleKeyEvent(escape))
        assertEquals(emptyList(), controller.intents)
    }

    @Test
    fun `an unregistered outgoing router no longer shadows the active router`() {
        val host = NavigationHost()
        val root = entry("root")
        val active = controller()
        val outgoing = controller()
        host.registerRouter(active, listOf(root, entry("active"))) { true }
        val unregisterOutgoing =
            host.registerRouter(outgoing, listOf(root, entry("old-parent"), entry("old-detail"))) { true }
        unregisterOutgoing()

        assertTrue(host.handleKeyEvent(escape))
        assertEquals<List<NavigationIntent>>(listOf(NavigationIntent.Pop), active.intents)
        assertEquals(emptyList(), outgoing.intents)
    }
}
