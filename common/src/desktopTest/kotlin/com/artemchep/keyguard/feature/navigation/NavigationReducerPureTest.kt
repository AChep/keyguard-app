package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The reducer over plain states: no entries, no coroutines.
 */
class NavigationReducerPureTest {
    private val root = NavigationEntryConfig(id = ROUTER_ID, route = RootRoute)
    private val a = NavigationEntryConfig(id = "a", route = ItemRoute("a"))

    private val rootStackId = NavigationStack.createId(
        prefix = ROUTER_ID,
        suffix = NavigationStack.createIdSuffix(RootRoute),
    )

    private val state = NavigationPileState(
        stacks = persistentListOf(
            NavigationStackState(
                id = rootStackId,
                entries = persistentListOf(root, a),
            ),
        ),
    )

    @Test
    fun `an empty pile asks the parent to pop`() {
        val empty = NavigationPileState(stacks = persistentListOf())

        val reduction = empty.reduce(ROUTER_ID, NavigationIntent.NavigateToRoute(ItemRoute("b")), ::newId)

        assertSame(NavigationReduction.PopParent, reduction)
    }

    @Test
    fun `a push keeps the configs it does not touch and adds a new one`() {
        val reduction = state.reduce(ROUTER_ID, NavigationIntent.NavigateToRoute(ItemRoute("b")), ::newId)

        val entries = assertIs<NavigationReduction.Handled>(reduction).pile.stacks.single().entries
        assertSame(root, entries[0])
        assertSame(a, entries[1])
        assertEquals("id-1", entries[2].id)
        assertEquals(ItemRoute("b"), entries[2].route)
        // The input state does not change.
        assertEquals(listOf(root, a), state.stacks.single().entries)
    }

    @Test
    fun `a handled intent that changes nothing is still handled`() {
        val intent = NavigationIntent.NavigateToStack(
            id = NavigationStack.createIdSuffix(RootRoute),
            root = RootRoute,
        )

        val reduction = state.reduce(ROUTER_ID, intent, ::newId)

        assertEquals(NavigationReduction.Handled(state), reduction)
    }

    @Test
    fun `NavigateToStack inside a composite reads the stacks from before the composite`() {
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.NavigateToRoute(ItemRoute("b")),
                NavigationIntent.NavigateToStack(
                    id = NavigationStack.createIdSuffix(RootRoute),
                    root = RootRoute,
                ),
            ),
        )

        // The second intent reads the active stack without b.
        val reduction = state.reduce(ROUTER_ID, intent, ::newId)

        assertEquals(NavigationReduction.Handled(state), reduction)
    }

    @Test
    fun `NavigateToStack of a missing stack adds it as the last stack`() {
        val intent = NavigationIntent.NavigateToStack("x", ItemRoute("x"))

        val reduction = state.reduce(ROUTER_ID, intent, ::newId)

        val stacks = assertIs<NavigationReduction.Handled>(reduction).pile.stacks
        assertEquals(listOf(rootStackId, NavigationStack.createId(ROUTER_ID, "x")), stacks.map { it.id })
        assertSame(state.stacks.single(), stacks.first())
        val newRoot = stacks.last().entries.single()
        assertEquals(ROUTER_ID, newRoot.id)
        assertEquals(ItemRoute("x"), newRoot.route)
    }

    @Test
    fun `ReplaceRoot keeps the configs above the root`() {
        val reduction = state.reduce(ROUTER_ID, NavigationIntent.ReplaceRoot(ItemRoute("root")), ::newId)

        val entries = assertIs<NavigationReduction.Handled>(reduction).pile.stacks.single().entries
        assertNotSame(root, entries[0])
        assertEquals(ROUTER_ID, entries[0].id)
        assertEquals(ItemRoute("root"), entries[0].route)
        assertSame(a, entries[1])
    }

    @Test
    fun `ReplaceRoot of an empty stack goes to the parent`() {
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(ROUTER_ID, exclusive = false),
                NavigationIntent.ReplaceRoot(ItemRoute("root")),
            ),
        )

        val reduction = state.reduce(ROUTER_ID, intent, ::newId)

        assertSame(NavigationReduction.Unhandled, reduction)
    }

    private var lastId = 0

    private fun newId(): String = "id-${++lastId}"

    private object RootRoute : Route {
        @Composable
        override fun Content() = Unit
    }

    private data class ItemRoute(val name: String) : Route {
        @Composable
        override fun Content() = Unit
    }

    private companion object {
        const val ROUTER_ID = "router"
    }
}
