package com.artemchep.keyguard.feature.navigation

import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationBackInterceptionTest : NavigationBackTestFixture() {
    @Test
    fun `root route can handle Back through its own interceptor`() {
        val root = entry("root")
        val pile = pile(root)
        var cleared = false
        val unregister = root.interceptBackPress { cleared = true }
        val controller = controller {
            assertTrue(pile.value.last().toImmutableModel().interceptBackPress())
        }
        val handler = BackHandler()
        handler.register(controller, listOf(root), pile::canPop)

        assertTrue(handler.handleKeyEvent(escape))
        assertTrue(cleared)
        assertEquals<List<NavigationEntry>>(listOf(root), pile.value.last().value)

        unregister()
        assertFalse(handler.handleKeyEvent(escape))
    }

    @Test
    fun `split pane search interceptor cannot take Back from item details`() {
        val list = entry("list")
        val detail = entry("detail")
        var query = "example"
        list.interceptBackPress { query = "" }
        val pile = pile(list, detail)
        val controller = controller {
            val stack = pile.value.last()
            assertFalse(stack.toImmutableModel().interceptBackPress())
            stack.value = listOf(list).toPersistentList()
        }
        val handler = BackHandler()
        handler.register(controller, listOf(list, detail), pile::canPop)

        assertTrue(handler.handleKeyEvent(escape))
        assertEquals<List<NavigationEntry>>(listOf(list), pile.value.last().value)
        assertEquals("example", query)
    }

    @Test
    fun `top route Back interceptor runs before navigation`() {
        val list = entry("list")
        val editor = entry("editor")
        var listBackCalls = 0
        var editorBackCalls = 0
        list.interceptBackPress { listBackCalls++ }
        editor.interceptBackPress { editorBackCalls++ }
        val stack = pile(list, editor).value.last().toImmutableModel()

        assertTrue(stack.interceptBackPress())
        assertEquals(0, listBackCalls)
        assertEquals(1, editorBackCalls)
    }

    @Test
    fun `second Escape can clear root search before the router recomposes`() {
        val list = entry("list")
        val detail = entry("detail")
        var query = "example"
        list.interceptBackPress { query = "" }
        val pile = pile(list, detail)
        val controller = controller {
            val stack = pile.value.last()
            if (!stack.toImmutableModel().interceptBackPress()) {
                stack.value = listOf(list).toPersistentList()
            }
        }
        val handler = BackHandler()
        handler.register(controller, listOf(list, detail), pile::canPop)

        assertTrue(handler.handleKeyEvent(escape))
        assertEquals("example", query)
        // The registration still contains the removed detail entry.
        assertTrue(handler.handleKeyEvent(escape))
        assertEquals("", query)
        assertEquals<List<NavigationEntry>>(listOf(list), pile.value.last().value)
    }

    @Test
    fun `nested dialog without an interceptor does not invoke an underlying handler`() {
        val detail = entry("detail")
        val firstDialog = entry("first-dialog", TestDialogRoute)
        val topDialog = entry("top-dialog", TestDialogRoute)
        var underlyingBackCalls = 0
        detail.interceptBackPress { underlyingBackCalls++ }
        firstDialog.interceptBackPress { underlyingBackCalls++ }
        val stack = pile(detail, firstDialog, topDialog).value.last().toImmutableModel()

        assertFalse(stack.interceptBackPress())
        assertEquals(0, underlyingBackCalls)
    }
}
