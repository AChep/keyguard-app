package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.apple.RecordingNavigationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The thread a chain of controllers handles its intents on, and when.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// One function per scenario.
@Suppress("TooManyFunctions")
class NavigationExecutorTest {
    private val hostScope = CoroutineScope(SupervisorJob())

    private var mainThread: MainThread? = null

    private var driver: NavigationRouterTestDriver? = null

    @AfterTest
    fun tearDown() {
        hostScope.cancel()
        Dispatchers.resetMain()
        mainThread?.close()
        driver?.close()
    }

    @Test
    fun `an intent queued off the executor thread is handled when its posted task runs`() {
        val executor = FakeExecutor(isCurrent = false)
        val handled = mutableListOf<NavigationIntent>()
        val controller = controller(executor = executor) { intent ->
            handled += intent
            null
        }

        controller.queue(X)
        controller.queue(Y)

        assertEquals(emptyList(), handled)
        executor.runAll()
        assertEquals(listOf(X, Y), handled)
    }

    @Test
    fun `a leftover of a posted intent reaches the parent inside the same posted task`() {
        val executor = FakeExecutor(isCurrent = false)
        val log = mutableListOf<String>()
        val parent = controller(executor = executor) { intent ->
            log += "parent $intent"
            null
        }
        val child = controller(parent) { intent ->
            log += "child $intent"
            intent
        }

        child.queue(X)
        assertEquals(1, executor.pending)
        executor.runNext()

        assertEquals(listOf("child $X", "parent $X"), log)
        assertEquals(0, executor.pending)
    }

    @Test
    fun `an intent queued on the executor thread is handled before queue returns`() {
        val executor = FakeExecutor(isCurrent = true)
        val handled = mutableListOf<NavigationIntent>()
        val controller = controller(executor = executor) { intent ->
            handled += intent
            null
        }

        controller.queue(X)

        assertEquals(listOf(X), handled)
        assertEquals(0, executor.pending)
    }

    @Test
    fun `an intent queued on the executor thread does not wait for posted intents`() {
        val executor = FakeExecutor(isCurrent = false)
        val handled = mutableListOf<NavigationIntent>()
        val controller = controller(executor = executor) { intent ->
            handled += intent
            null
        }
        controller.queue(X)

        executor.isCurrent = true
        controller.queue(Y)
        executor.runAll()

        assertEquals(listOf(Y, X), handled)
    }

    @Test
    fun `a child uses the executor of its chain, not its own argument`() {
        val rootExecutor = FakeExecutor(isCurrent = false)
        val childExecutor = FakeExecutor(isCurrent = true)
        val handled = mutableListOf<NavigationIntent>()
        val root = controller(executor = rootExecutor) { intent ->
            handled += intent
            null
        }
        val child = controller(root, executor = childExecutor) { it }

        child.queue(X)

        assertEquals(emptyList(), handled)
        assertEquals(1, rootExecutor.pending)
        rootExecutor.runAll()
        assertEquals(listOf(X), handled)
        assertEquals(0, childExecutor.pending)
    }

    @Test
    fun `the main executor handles an intent when the main dispatcher runs it`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val handled = mutableListOf<NavigationIntent>()
        val controller = controller { intent ->
            handled += intent
            null
        }

        controller.queue(X)
        controller.queue(Y)

        assertEquals(emptyList(), handled)
        main.scheduler.advanceUntilIdle()
        assertEquals(listOf(X, Y), handled)
    }

    @Test
    fun `the main executor drops an intent whose scope is cancelled before it runs`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val handled = mutableListOf<NavigationIntent>()
        val controller = controller { intent ->
            handled += intent
            null
        }

        controller.queue(X)
        hostScope.cancel()
        main.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), handled)
    }

    @Test
    fun `an intent queued off the main thread changes the router on the main thread`() {
        val main = MainThread().also { this.mainThread = it }
        Dispatchers.setMain(main.dispatcher)
        val driver = NavigationRouterTestDriver(ROUTER_ID, RootRoute).also { this.driver = it }
        val handledOn = Collections.synchronizedList(mutableListOf<Thread>())
        val createdOn = ConcurrentHashMap<String, Thread>()
        val controller = controller { intent ->
            handledOn += Thread.currentThread()
            val before = driver.entryIds().toSet()
            val leftover = driver.dispatch(intent)
            (driver.entryIds() - before).forEach { id ->
                createdOn[id] = Thread.currentThread()
            }
            leftover
        }

        controller.queue(NavigationIntent.NavigateToRoute(ItemRoute("a")))
        main.awaitIdle()

        assertEquals(listOf(RootRoute, ItemRoute("a")), driver.routes())
        val created = driver.entryIds().last()
        assertEquals(mapOf(created to main.thread), createdOn.toMap())

        var destroyedOn: Thread? = null
        driver.entry(created).scope.coroutineContext[Job]!!.invokeOnCompletion {
            destroyedOn = Thread.currentThread()
        }
        controller.queue(NavigationIntent.Pop)
        main.awaitIdle()

        assertEquals(listOf(RootRoute), driver.routes())
        assertSame(main.thread, destroyedOn)
        assertEquals(listOf(main.thread, main.thread), handledOn.toList())
    }

    @Test
    fun `the main executor is current on the Swing event thread only`() {
        // The desktop app's own main dispatcher.
        Dispatchers.resetMain()

        var onEventThread = false
        SwingUtilities.invokeAndWait {
            onEventThread = MainNavigationExecutor.isCurrent
        }

        assertTrue(onEventThread)
        assertFalse(MainNavigationExecutor.isCurrent)
    }

    private fun controller(
        parent: NavigationController = RecordingNavigationController(hostScope),
        executor: NavigationExecutor = MainNavigationExecutor,
        handle: (NavigationIntent) -> NavigationIntent?,
    ): NavigationController = createNavigationController(
        parent = parent,
        scope = hostScope,
        canPop = flowOf(false),
        handle = handle,
        executor = executor,
    )

    /**
     * Records the posted tasks; a test runs them. [isCurrent] says
     * whether the test plays the executor's thread.
     */
    private class FakeExecutor(
        override var isCurrent: Boolean,
    ) : NavigationExecutor {
        private val tasks = ArrayDeque<() -> Unit>()

        val pending: Int
            get() = tasks.size

        override fun post(scope: CoroutineScope, task: () -> Unit) {
            tasks.addLast(task)
        }

        fun runNext() = tasks.removeFirst().invoke()

        fun runAll() {
            while (tasks.isNotEmpty()) {
                runNext()
            }
        }
    }

    /** A single thread that plays the main thread. */
    private class MainThread : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "test-main")
        }

        val dispatcher = executor.asCoroutineDispatcher()

        val thread: Thread = executor
            .submit(Callable { Thread.currentThread() })
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        /** Waits until every task posted so far has run. */
        fun awaitIdle() {
            executor
                .submit(Callable { })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        override fun close() = dispatcher.close()
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
        const val TIMEOUT_SECONDS = 10L

        val X: NavigationIntent = NavigationIntent.NavigateToBrowser("https://example.com/x")
        val Y: NavigationIntent = NavigationIntent.NavigateToBrowser("https://example.com/y")
    }
}
