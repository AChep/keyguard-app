package com.artemchep.keyguard.feature.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationBackInterceptionTest : NavigationBackTestFixture() {
    @Test
    fun `root route can handle Back through its own interceptor`() {
        val pile = pile("root")
        val root = pile.entry("root")
        var cleared = false
        val unregister = root.interceptBackPress { cleared = true }
        val controller = controller {
            assertTrue(pile.value.last().interceptBackPress())
        }
        val host = NavigationHost()
        host.registerRouter(controller, listOf(root), pile::canPop)

        assertTrue(host.handleKeyEvent(escape))
        assertTrue(cleared)
        assertEquals<List<NavigationEntry>>(listOf(root), pile.value.last().value)

        unregister()
        assertFalse(host.handleKeyEvent(escape))
    }

    @Test
    fun `split pane search interceptor cannot take Back from item details`() {
        val pile = pile("list", "detail")
        val list = pile.entry("list")
        val detail = pile.entry("detail")
        var query = "example"
        list.interceptBackPress { query = "" }
        val controller = controller {
            val stack = pile.value.last()
            assertFalse(stack.interceptBackPress())
            assertNull(pile.dispatch(NavigationIntent.Pop))
        }
        val host = NavigationHost()
        host.registerRouter(controller, listOf(list, detail), pile::canPop)

        assertTrue(host.handleKeyEvent(escape))
        assertEquals<List<NavigationEntry>>(listOf(list), pile.value.last().value)
        assertEquals("example", query)
    }

    @Test
    fun `top route Back interceptor runs before navigation`() {
        val pile = pile("list", "editor")
        val list = pile.entry("list")
        val editor = pile.entry("editor")
        var listBackCalls = 0
        var editorBackCalls = 0
        list.interceptBackPress { listBackCalls++ }
        editor.interceptBackPress { editorBackCalls++ }
        val stack = pile.value.last()

        assertTrue(stack.interceptBackPress())
        assertEquals(0, listBackCalls)
        assertEquals(1, editorBackCalls)
    }

    @Test
    fun `second Escape can clear root search before the router recomposes`() {
        val pile = pile("list", "detail")
        val list = pile.entry("list")
        val detail = pile.entry("detail")
        var query = "example"
        list.interceptBackPress { query = "" }
        val controller = controller {
            // The top route intercepts Back first, otherwise the stack pops.
            pile.dispatch(NavigationIntent.Pop)
        }
        val host = NavigationHost()
        host.registerRouter(controller, listOf(list, detail), pile::canPop)

        assertTrue(host.handleKeyEvent(escape))
        assertEquals("example", query)
        // The registration still contains the removed detail entry.
        assertTrue(host.handleKeyEvent(escape))
        assertEquals("", query)
        assertEquals<List<NavigationEntry>>(listOf(list), pile.value.last().value)
    }

    @Test
    fun `nested dialog without an interceptor does not invoke an underlying handler`() {
        val pile = pile(
            "detail" to TestRoute,
            "first-dialog" to TestDialogRoute,
            "top-dialog" to TestDialogRoute,
        )
        val detail = pile.entry("detail")
        val firstDialog = pile.entry("first-dialog")
        var underlyingBackCalls = 0
        detail.interceptBackPress { underlyingBackCalls++ }
        firstDialog.interceptBackPress { underlyingBackCalls++ }
        val stack = pile.value.last()

        assertFalse(stack.interceptBackPress())
        assertEquals(0, underlyingBackCalls)
    }
}
