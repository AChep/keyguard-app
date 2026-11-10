package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
// One function per scenario.
@Suppress("TooManyFunctions")
class NavigationReducerTest {
    private val routers = mutableListOf<NavigationRouterTestDriver>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        routers.forEach { it.close() }
        Dispatchers.resetMain()
    }

    private fun router(): NavigationRouterTestDriver =
        NavigationRouterTestDriver(routerId = ROUTER_ID, initial = RootRoute)
            .also(routers::add)

    private fun NavigationRouterTestDriver.push(vararg routes: Route): List<String> =
        routes.map { route ->
            assertNull(dispatch(NavigationIntent.NavigateToRoute(route)))
            entryIds().last()
        }

    @Test
    fun `NavigateToRoute appends an entry with a fresh id`() {
        val router = router()
        assertEquals(listOf(ROUTER_ID), router.entryIds())

        assertNull(router.dispatch(NavigationIntent.NavigateToRoute(ItemRoute("a"))))
        assertNull(router.dispatch(NavigationIntent.NavigateToRoute(ItemRoute("a"))))

        assertEquals(listOf(RootRoute, ItemRoute("a"), ItemRoute("a")), router.routes())
        val ids = router.entryIds()
        assertEquals(ROUTER_ID, ids.first())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf(router.stackId(RootRoute)), router.stackIds)
    }

    @Test
    fun `NavigateToRoute SINGLE moves the entry of an equal route to the top`() {
        val router = router()
        val (a, other, b) = router.push(ItemRoute("a"), OtherRoute, ItemRoute("b"))
        val entryA = router.entry(a)
        val entryB = router.entry(b)

        val intent = NavigationIntent.NavigateToRoute(
            route = ItemRoute("a"),
            launchMode = NavigationIntent.NavigateToRoute.LaunchMode.SINGLE,
        )
        assertNull(router.dispatch(intent))

        assertEquals(listOf(ROUTER_ID, other, a), router.entryIds())
        assertEquals(listOf(RootRoute, OtherRoute, ItemRoute("a")), router.routes())
        assertSame(entryA, router.entry(a))
        assertFalse(entryA.isDestroyed)
        assertTrue(entryB.isDestroyed)
    }

    @Test
    fun `NavigateToRoute SINGLE replaces entries of the same route class with a new entry`() {
        val router = router()
        val (a, other) = router.push(ItemRoute("a"), OtherRoute)
        val entryA = router.entry(a)

        val intent = NavigationIntent.NavigateToRoute(
            route = ItemRoute("c"),
            launchMode = NavigationIntent.NavigateToRoute.LaunchMode.SINGLE,
        )
        assertNull(router.dispatch(intent))

        val ids = router.entryIds()
        assertEquals(listOf(ROUTER_ID, other), ids.dropLast(1))
        assertFalse(ids.last() in setOf(ROUTER_ID, a, other))
        assertEquals(listOf(RootRoute, OtherRoute, ItemRoute("c")), router.routes())
        assertTrue(entryA.isDestroyed)
    }

    @Test
    fun `SetRoute replaces the active stack with one new entry`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val root = router.entry(ROUTER_ID)
        val entryA = router.entry(a)

        assertNull(router.dispatch(NavigationIntent.SetRoute(ItemRoute("b"))))

        assertEquals(listOf(ItemRoute("b")), router.routes())
        assertFalse(router.entryIds().single() in setOf(ROUTER_ID, a))
        assertEquals(listOf(router.stackId(RootRoute)), router.stackIds)
        assertTrue(root.isDestroyed)
        assertTrue(entryA.isDestroyed)
    }

    @Test
    fun `Pop removes and destroys the top entry`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val root = router.entry(ROUTER_ID)
        val entryA = router.entry(a)

        assertNull(router.dispatch(NavigationIntent.Pop))

        assertEquals(listOf(ROUTER_ID), router.entryIds())
        assertTrue(entryA.isDestroyed)
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `Pop goes to the back interceptor of the top entry first`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)
        var calls = 0
        entryA.interceptBackPress { calls++ }

        assertNull(router.dispatch(NavigationIntent.Pop))

        assertEquals(1, calls)
        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertFalse(entryA.isDestroyed)
    }

    @Test
    fun `Pop does not consult the back interceptor of an entry below the top`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)
        var rootCalls = 0
        router.entry(ROUTER_ID).interceptBackPress { rootCalls++ }

        assertNull(router.dispatch(NavigationIntent.Pop))

        assertEquals(0, rootCalls)
        assertEquals(listOf(ROUTER_ID), router.entryIds())
        assertTrue(entryA.isDestroyed)
    }

    @Test
    fun `PopById exclusive keeps the target entry`() {
        val router = router()
        val (a, b, c) = router.push(ItemRoute("a"), ItemRoute("b"), ItemRoute("c"))
        val entryA = router.entry(a)
        val entryB = router.entry(b)
        val entryC = router.entry(c)

        assertNull(router.dispatch(NavigationIntent.PopById(a, exclusive = true)))

        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertFalse(entryA.isDestroyed)
        assertTrue(entryB.isDestroyed)
        assertTrue(entryC.isDestroyed)
    }

    @Test
    fun `PopById inclusive removes the target entry`() {
        val router = router()
        val (a, b) = router.push(ItemRoute("a"), ItemRoute("b"))
        val root = router.entry(ROUTER_ID)
        val entryA = router.entry(a)
        val entryB = router.entry(b)

        assertNull(router.dispatch(NavigationIntent.PopById(a, exclusive = false)))

        assertEquals(listOf(ROUTER_ID), router.entryIds())
        assertFalse(root.isDestroyed)
        assertTrue(entryA.isDestroyed)
        assertTrue(entryB.isDestroyed)
    }

    @Test
    fun `PopById of an unknown id goes to the parent and changes nothing`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)

        val intent = NavigationIntent.PopById("unknown")
        assertSame(intent, router.dispatch(intent))

        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertFalse(entryA.isDestroyed)
    }

    @Test
    fun `Composite applies its intents in order as one update`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val root = router.entry(ROUTER_ID)
        val entryA = router.entry(a)

        // The stack is empty after the second Pop. As one update,
        // that intermediate state never reaches the parent.
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.Pop,
                NavigationIntent.Pop,
                NavigationIntent.NavigateToRoute(ItemRoute("b")),
                NavigationIntent.NavigateToRoute(ItemRoute("c")),
            ),
        )
        assertNull(router.dispatch(intent))

        assertEquals(listOf(ItemRoute("b"), ItemRoute("c")), router.routes())
        assertTrue(root.isDestroyed)
        assertTrue(entryA.isDestroyed)
    }

    @Test
    fun `Composite with an unhandled intent goes to the parent whole`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)

        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.Pop,
                NavigationIntent.NavigateToBrowser("https://example.com"),
            ),
        )
        assertSame(intent, router.dispatch(intent))

        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertFalse(entryA.isDestroyed)
    }

    @Test
    fun `NavigateToStack of a missing stack creates it with the router id as the root id`() {
        val router = router()
        val initialStackId = router.stackId(RootRoute)
        val root = router.entry(ROUTER_ID)

        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("x"))))

        assertEquals(listOf(initialStackId, router.stackId("x")), router.stackIds)
        assertEquals(listOf(TabRoute("x")), router.routes())
        assertEquals(listOf(ROUTER_ID), router.entryIds())
        assertNotSame(root, router.entries().single())
        assertEquals(listOf(ROUTER_ID), router.entryIds(initialStackId))
        assertSame(root, router.entries(initialStackId).single())
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `NavigateToStack of a stack whose root has the same descriptor keeps it with its entries`() {
        val router = router()
        val initialStackId = router.stackId(RootRoute)
        val root = router.entry(ROUTER_ID)
        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("x"))))
        val rootX = router.entries().single()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)

        // The default id of the initial stack ends with the class name of its route.
        val toInitial = NavigationIntent.NavigateToStack(
            id = NavigationStack.createIdSuffix(RootRoute),
            root = RootRoute,
        )
        assertNull(router.dispatch(toInitial))

        assertEquals(listOf(router.stackId("x"), initialStackId), router.stackIds)
        assertEquals(listOf(root), router.entries())

        // The routes differ, but their descriptors are the same.
        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("x", title = "renamed"))))

        assertEquals(listOf(initialStackId, router.stackId("x")), router.stackIds)
        assertEquals(listOf(rootX, entryA), router.entries())
        assertEquals(listOf(TabRoute("x"), ItemRoute("a")), router.routes())
        listOf(root, rootX, entryA).forEach { assertFalse(it.isDestroyed) }
    }

    @Test
    fun `NavigateToStack of a stack whose root has another descriptor resets it`() {
        val router = router()
        val initialStackId = router.stackId(RootRoute)
        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("x"))))
        val oldRootX = router.entries().single()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)
        val toInitial = NavigationIntent.NavigateToStack(
            id = NavigationStack.createIdSuffix(RootRoute),
            root = RootRoute,
        )
        assertNull(router.dispatch(toInitial))

        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("y"))))

        assertEquals(listOf(initialStackId, router.stackId("x")), router.stackIds)
        assertEquals(listOf(TabRoute("y")), router.routes())
        assertEquals(listOf(ROUTER_ID), router.entryIds())
        val newRootX = router.entries().single()
        assertNotSame(oldRootX, newRootX)
        assertFalse(newRootX.isDestroyed)
        assertTrue(oldRootX.isDestroyed)
        assertTrue(entryA.isDestroyed)
    }

    @Test
    fun `NavigateToStack of the initial stack id creates no second stack`() {
        val router = NavigationRouterTestDriver(
            routerId = ROUTER_ID,
            initial = TabRoute("home"),
            initialStackId = "home",
        ).also(routers::add)
        val root = router.entry(ROUTER_ID)
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)

        assertNull(router.dispatch(NavigationIntent.NavigateToStack("other", TabRoute("other"))))
        assertNull(router.dispatch(NavigationIntent.NavigateToStack("home", TabRoute("home"))))

        assertEquals(listOf(router.stackId("other"), router.stackId("home")), router.stackIds)
        assertEquals(listOf(root, entryA), router.entries())
        assertFalse(root.isDestroyed)
        assertFalse(entryA.isDestroyed)
    }

    @Test
    fun `Composite continues on the stack that NavigateToStack activates`() {
        val router = router()
        val initialStackId = router.stackId(RootRoute)
        val root = router.entry(ROUTER_ID)

        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.NavigateToStack("x", TabRoute("x")),
                NavigationIntent.NavigateToRoute(ItemRoute("a")),
                NavigationIntent.ReplaceRoot(TabRoute("x", title = "renamed")),
            ),
        )
        assertNull(router.dispatch(intent))

        assertEquals(listOf(initialStackId, router.stackId("x")), router.stackIds)
        assertEquals(listOf(TabRoute("x", title = "renamed"), ItemRoute("a")), router.routes())
        assertEquals(ROUTER_ID, router.entryIds().first())
        assertEquals(listOf(root), router.entries(initialStackId))
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `Pop of the only entry of the active stack re-activates the previous stack`() {
        val router = router()
        val initialStackId = router.stackId(RootRoute)
        val root = router.entry(ROUTER_ID)
        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("x"))))
        val entryX = router.entries().single()

        assertNull(router.dispatch(NavigationIntent.Pop))

        assertEquals(listOf(initialStackId), router.stackIds)
        assertEquals(listOf(ROUTER_ID), router.entryIds())
        assertTrue(entryX.isDestroyed)
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `platform intents go to the parent and change nothing`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)

        val intents = listOf(
            NavigationIntent.NavigateToBrowser("https://example.com"),
            NavigationIntent.NavigateToShare("text"),
            NavigationIntent.Exit,
        )
        intents.forEach { intent ->
            assertSame(intent, router.dispatch(intent))
        }

        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertEquals(listOf(router.stackId(RootRoute)), router.stackIds)
        assertFalse(entryA.isDestroyed)
    }

    @Test
    fun `ReplaceRoot keeps the root id and the entries above and destroys the old root`() {
        // This is what HomeScreen does when a dynamic tab changes its root route.
        val router = router()
        val oldRoot = router.entry(ROUTER_ID)
        val (a) = router.push(ItemRoute("a"))
        val entryA = router.entry(a)

        assertNull(router.dispatch(NavigationIntent.ReplaceRoot(ItemRoute("new root"))))

        val newRoot = router.entry(ROUTER_ID)
        assertNotSame(oldRoot, newRoot)
        assertEquals(listOf(router.stackId(RootRoute)), router.stackIds)
        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertEquals(listOf(ItemRoute("new root"), ItemRoute("a")), router.routes())
        assertSame(entryA, router.entry(a))
        assertFalse(newRoot.isDestroyed)
        assertFalse(entryA.isDestroyed)
        assertTrue(oldRoot.isDestroyed)
    }

    @Test
    fun `ReplaceRoot changes the active stack only`() {
        val router = router()
        val initialStackId = router.stackId(RootRoute)
        val root = router.entry(ROUTER_ID)
        assertNull(router.dispatch(NavigationIntent.NavigateToStack("x", TabRoute("x"))))
        val rootX = router.entries().single()

        assertNull(router.dispatch(NavigationIntent.ReplaceRoot(TabRoute("y"))))

        assertEquals(listOf(initialStackId, router.stackId("x")), router.stackIds)
        assertEquals(listOf(TabRoute("y")), router.routes())
        assertEquals(listOf(root), router.entries(initialStackId))
        assertFalse(root.isDestroyed)
        assertTrue(rootX.isDestroyed)
    }

    @Test
    fun `Pop of the only entry asks the parent to pop and keeps the router intact`() {
        val router = router()
        val root = router.entry(ROUTER_ID)

        assertEquals(NavigationIntent.Pop, router.dispatch(NavigationIntent.Pop))

        assertEquals(listOf(router.stackId(RootRoute)), router.stackIds)
        assertEquals(listOf(ROUTER_ID), router.entryIds())
        assertSame(root, router.entry(ROUTER_ID))
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `PopById of the root asks the parent to pop and keeps the router intact`() {
        val router = router()
        val (a) = router.push(ItemRoute("a"))
        val root = router.entry(ROUTER_ID)
        val entryA = router.entry(a)

        val intent = NavigationIntent.PopById(ROUTER_ID, exclusive = false)
        assertEquals(NavigationIntent.Pop, router.dispatch(intent))

        assertEquals(listOf(router.stackId(RootRoute)), router.stackIds)
        assertEquals(listOf(ROUTER_ID, a), router.entryIds())
        assertFalse(root.isDestroyed)
        assertFalse(entryA.isDestroyed)
    }

    private object RootRoute : Route {
        @Composable
        override fun Content() = Unit
    }

    private object OtherRoute : Route {
        @Composable
        override fun Content() = Unit
    }

    private data class ItemRoute(val name: String) : Route {
        @Composable
        override fun Content() = Unit
    }

    /** A route whose descriptor is its [name], whatever its [title]. */
    private data class TabRoute(
        val name: String,
        val title: String = name,
    ) : Route {
        override val descriptor: RouteDescriptor
            get() = RouteDescriptor.Unmapped(name)

        @Composable
        override fun Content() = Unit
    }

    private companion object {
        const val ROUTER_ID = "router"
    }
}
