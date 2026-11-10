package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A chain of controllers built without Compose: the order intents reach
 * the handlers in, and what the routers end up with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// One function per scenario.
@Suppress("TooManyFunctions")
class NavigationControllerTest {
    private val hostJob = SupervisorJob()
    private val hostScope = CoroutineScope(hostJob + Dispatchers.Unconfined)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        hostScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `an unhandled intent reaches the parent within the same call`() {
        val handled = mutableListOf<NavigationIntent>()
        val parent = controller { intent ->
            handled += intent
            null
        }
        val child = controller(parent) { it }

        child.queue(X)

        assertEquals(listOf(X), handled)
    }

    @Test
    fun `a leftover reaches the parent before an intent the child queued while handling`() {
        val handled = mutableListOf<NavigationIntent>()
        val parent = controller { intent ->
            handled += intent
            null
        }
        lateinit var child: NavigationController
        child = controller(parent) { intent ->
            if (intent == X) {
                child.queue(Y)
            }
            intent
        }

        child.queue(X)

        assertEquals(listOf(X, Y), handled)
    }

    @Test
    fun `a foreign parent receives the leftover through its queue`() {
        val foreign = RecordingController(hostScope)
        val root = controller(foreign) { it }
        val child = controller(root) { it }

        child.queue(X)

        assertEquals(listOf(X), foreign.intents)
    }

    @Test
    fun `a child shares the relay of its parent`() {
        val log = mutableListOf<String>()
        val parent = controller { intent ->
            log += "parent $intent"
            null
        }
        val child = controller(parent) {
            log += "child start"
            parent.queue(Y)
            log += "child end"
            null
        }

        child.queue(X)

        assertEquals(listOf("child start", "child end", "parent $Y"), log)
    }

    @Test
    fun `an unrelated root handles an intent at once while another root is busy`() {
        val log = mutableListOf<String>()
        val other = controller { intent ->
            log += "other $intent"
            null
        }
        val root = controller {
            log += "root start"
            other.queue(Y)
            log += "root end"
            null
        }

        root.queue(X)

        assertEquals(listOf("root start", "other $Y", "root end"), log)
    }

    @Test
    fun `an unrelated root is not blocked by a root that another thread drains`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val busy = controller {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            null
        }
        val caller = Thread.currentThread()
        var handledOn: Thread? = null
        val other = controller {
            handledOn = Thread.currentThread()
            null
        }

        val drainer = thread(name = "drainer") { busy.queue(X) }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))

            other.queue(Y)

            assertSame(caller, handledOn)
        } finally {
            release.countDown()
            drainer.join(10_000)
        }
        assertFalse(drainer.isAlive)
    }

    @Test
    fun `a Back interceptor that navigates while handling Pop has its navigation applied`() {
        val pile = pile(ROUTER_ID)
        lateinit var controller: NavigationController
        controller = controller { pile.dispatch(it) }
        var routesInInterceptor: List<Route>? = null
        pile.entry(ROUTER_ID).interceptBackPress {
            controller.queue(NavigationIntent.NavigateToRoute(ItemRoute("b")))
            routesInInterceptor = pile.routes()
        }

        controller.queue(NavigationIntent.Pop)

        assertEquals(listOf(RootRoute), routesInInterceptor)
        assertEquals(listOf(RootRoute, ItemRoute("b")), pile.routes())
    }

    @Test
    fun `a navigation queued from a destroy callback applies after the current change`() {
        val pile = pile(ROUTER_ID)
        val log = mutableListOf<String>()
        lateinit var controller: NavigationController
        controller = controller { intent ->
            log += "handle $intent"
            pile.dispatch(intent)
        }
        controller.queue(NavigationIntent.NavigateToRoute(ItemRoute("a")))
        controller.queue(NavigationIntent.NavigateToRoute(ItemRoute("a2")))
        val (a, a2) = pile.value.last().value.takeLast(2)
        a.scope.coroutineContext[Job]!!.invokeOnCompletion {
            log += "destroyed a"
        }
        var routesInCallback: List<Route>? = null
        a2.scope.coroutineContext[Job]!!.invokeOnCompletion {
            log += "destroyed a2"
            controller.queue(NavigationIntent.NavigateToRoute(ItemRoute("b")))
            routesInCallback = pile.routes()
        }
        log.clear()

        val popToRoot = NavigationIntent.PopById(ROUTER_ID)
        controller.queue(popToRoot)

        val b = NavigationIntent.NavigateToRoute(ItemRoute("b"))
        assertEquals(
            listOf("handle $popToRoot", "destroyed a2", "destroyed a", "handle $b"),
            log,
        )
        assertEquals(listOf(RootRoute), routesInCallback)
        assertEquals(listOf(RootRoute, ItemRoute("b")), pile.routes())
    }

    @Test
    fun `a router with nothing to pop passes Pop to the parent router in the same call`() {
        val parentPile = pile("parent")
        val parent = controller { parentPile.dispatch(it) }
        parent.queue(NavigationIntent.NavigateToRoute(ItemRoute("a")))
        val childPile = pile("child")
        val child = controller(parent) { childPile.dispatch(it) }

        child.queue(NavigationIntent.Pop)

        assertEquals(listOf(RootRoute), parentPile.routes())
        assertEquals(listOf(RootRoute), childPile.routes())
    }

    private fun controller(
        parent: NavigationController = RecordingController(hostScope),
        handle: (NavigationIntent) -> NavigationIntent?,
    ): NavigationController = createNavigationController(
        parent = parent,
        scope = hostScope,
        canPop = flowOf(false),
        handle = handle,
    )

    private fun pile(routerId: String): NavigationPile = NavigationPile(
        id = routerId,
        routerId = routerId,
        parentScope = hostScope,
        initial = NavigationPileState(
            stacks = persistentListOf(
                NavigationStackState(
                    id = NavigationStack.createId(
                        prefix = routerId,
                        suffix = NavigationStack.createIdSuffix(RootRoute),
                    ),
                    entries = persistentListOf(
                        NavigationEntryConfig(
                            id = routerId,
                            route = RootRoute,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun NavigationPile.routes(): List<Route> = value.last().value.map { it.route }

    private fun NavigationPile.entry(id: String): NavigationEntry = value
        .flatMap { it.value }
        .single { it.id == id }

    private class RecordingController(
        override val scope: CoroutineScope,
    ) : NavigationController {
        val intents = mutableListOf<NavigationIntent>()

        override fun queue(intent: NavigationIntent) {
            intents += intent
        }

        override fun canPop(): Flow<Boolean> = flowOf(false)
    }

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

        val X: NavigationIntent = NavigationIntent.NavigateToBrowser("https://example.com/x")
        val Y: NavigationIntent = NavigationIntent.NavigateToBrowser("https://example.com/y")
    }
}
