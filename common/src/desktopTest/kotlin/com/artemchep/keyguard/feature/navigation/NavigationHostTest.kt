package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Density
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.platform.WindowId
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
class NavigationHostTest {
    //
    // Back targets
    //

    @Test
    fun `isBackEnabled follows the registered targets`() {
        val host = NavigationHost()
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }
        assertFalse(host.isBackEnabled)

        host.registerBackTarget(canPop = { false }) {}
        assertFalse(host.isBackEnabled)
        val unregister = host.registerBackTarget(canPop = { true }) {}
        assertTrue(host.isBackEnabled)
        unregister()
        assertFalse(host.isBackEnabled)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `invalidateBack picks up a target that became poppable`() {
        val host = NavigationHost()
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }
        var canPop = false
        host.registerBackTarget(canPop = { canPop }) {}

        canPop = true
        // Not evaluated again yet.
        assertFalse(host.isBackEnabled)
        host.invalidateBack()
        assertTrue(host.isBackEnabled)

        canPop = false
        host.invalidateBack()
        assertFalse(host.isBackEnabled)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `listeners hear only changes and nothing after removal`() {
        val host = NavigationHost()
        val changes = mutableListOf<Boolean>()
        val removeListener = host.addBackEnabledListener { changes += it }

        host.registerBackTarget(canPop = { true }) {}
        host.registerBackTarget(canPop = { true }) {}
        host.invalidateBack()
        assertEquals(listOf(true), changes)

        removeListener()
        val unregister = host.registerBackTarget(canPop = { false }) {}
        unregister()
        host.invalidateBack()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `a tie in depth goes to the latest registration`() {
        val host = NavigationHost()
        val calls = mutableListOf<String>()
        host.registerBackTarget(depth = { 1 }, canPop = { true }) { calls += "first" }
        val unregisterSecond = host.registerBackTarget(depth = { 1 }, canPop = { true }) { calls += "second" }
        host.registerBackTarget(depth = { 0 }, canPop = { true }) { calls += "shallow" }
        host.registerBackTarget(depth = { 2 }, canPop = { false }) { calls += "stuck" }

        assertTrue(host.handleBack())
        unregisterSecond()
        assertTrue(host.handleBack())
        assertEquals(listOf("second", "first"), calls)
    }

    //
    // Routers
    //

    @Test
    fun `a queued navigation enables Back before the next frame`() = withHarness {
        start(routerHost("outer", screen("list")))
        assertFalse(host.isBackEnabled)
        val changes = mutableListOf<Boolean>()
        host.addBackEnabledListener { changes += it }

        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        assertTrue(host.isBackEnabled)
        router("outer").controller.queue(NavigationIntent.Pop)
        assertFalse(host.isBackEnabled)
        // No frame ran in between.
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `a Back interceptor on the top entry enables Back`() = withHarness {
        start(routerHost("outer", screen("list")))
        assertFalse(host.isBackEnabled)

        val unregister = router("outer").entries.single().interceptBackPress {}
        frame()
        assertTrue(host.isBackEnabled)

        unregister()
        frame()
        assertFalse(host.isBackEnabled)
    }

    @Test
    fun `a router that left the composition no longer counts`() = withHarness {
        start(routerHost("outer", screen("list")))
        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        assertTrue(host.isBackEnabled)

        mainRoute = null
        frame()

        assertFalse(host.isBackEnabled)
        assertFalse(host.handleBack())
    }

    @Test
    fun `saveRouters saves the pile of a router and of a nested router`() = withHarness {
        start(routerHost("outer", routerHost("nested", screen("nested list"))))
        val outerPile = router("outer").pile
        val nestedPile = router("nested").pile
        val store = NavigationStore()

        host.saveRouters(store)

        assertSame(outerPile, store.pop(outerPile.id))
        assertSame(nestedPile, store.pop(nestedPile.id))

        // Another window has none of these routers.
        val otherStore = NavigationStore()
        NavigationHost().saveRouters(otherStore)
        assertNull(otherStore.pop(outerPile.id))
        assertNull(otherStore.pop(nestedPile.id))
    }

    private fun withHarness(block: Harness.() -> Unit) {
        try {
            SwingUtilities.invokeAndWait {
                val harness = Harness()
                try {
                    harness.block()
                } finally {
                    harness.close()
                }
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private class RouterProbe(
        private val hostEntry: NavigationEntry,
        val controller: NavigationController,
        val entries: List<NavigationEntry>,
    ) {
        val pile: NavigationPile
            get() = hostEntry.subStacks.values.single()
    }

    /**
     * Composes one root node in a window with its own [host], the way the
     * platform hosts do.
     */
    private class Harness {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val koin = Koin().apply {
            loadModules(listOf(TestModule(scope).module))
        }
        val host = NavigationHost()
        private val store = NavigationStore()
        private val rootCanPop = flowOf(false)
        private val rootHandle: (NavigationIntent) -> NavigationIntent? = { it }

        var mainRoute by mutableStateOf<Route?>(null)

        // Filled by the composition.
        private val routers = mutableMapOf<String, RouterProbe>()

        private var scene: ImageComposeScene? = null
        private var frameTime = 0L

        fun start(route: Route) {
            mainRoute = route
            scene = ImageComposeScene(
                width = 320,
                height = 480,
                density = Density(1f),
            ) {
                Content()
            }
            frame()
        }

        fun frame() {
            val scene = checkNotNull(scene)
            repeat(3) {
                scene.render(frameTime).close()
                frameTime += 1_000_000_000L
            }
        }

        fun router(id: String): RouterProbe = assertNotNull(routers[id], "Router $id is not composed")

        fun screen(name: String): Route = Screen(name)

        fun routerHost(routerId: String, initial: Route): Route = RouterHost(routerId, initial)

        fun close() {
            scene?.close()
            scope.cancel()
            koin.close()
        }

        @Composable
        private fun Content() {
            KoinContext(koin) {
                CompositionLocalProvider(
                    LocalNavigationStore provides store,
                ) {
                    NavigationHost(host) {
                        NavigationController(scope = scope, canPop = rootCanPop, handle = rootHandle) {
                            val route = mainRoute
                            if (route != null) {
                                NavigationNode(id = MAIN_ID, route = route)
                            }
                        }
                    }
                }
            }
        }

        private class Screen(private val name: String) : Route {
            @Composable
            override fun Content() = Unit

            override fun toString() = "Screen($name)"
        }

        private inner class RouterHost(
            private val routerId: String,
            private val initial: Route,
        ) : Route {
            @Composable
            override fun Content() {
                val hostEntry = LocalNavigationEntry.current
                NavigationRouter(id = routerId, initial = initial) { entries ->
                    val controller = LocalNavigationController.current
                    SideEffect { routers[routerId] = RouterProbe(hostEntry, controller, entries) }
                    NavigationNode(entries = entries)
                }
            }

            override fun toString() = "RouterHost($routerId)"
        }
    }

    private class TestModule(scope: CoroutineScope) {
        val module = module {
            single {
                NavigationRestoreRepository(NavigationStackPersistence(JsonKeyValueStore(), Json), scope)
            }
            single<KeyboardShortcutsServiceHost> {
                object : KeyboardShortcutsServiceHost {
                    override fun register(windowId: WindowId, block: (KeyEvent) -> Boolean): () -> Unit = {}
                }
            }
            single<GetNavAnimation> {
                object : GetNavAnimation {
                    override fun invoke() = MutableStateFlow(NavAnimation.DISABLED)
                }
            }
        }
    }

    private companion object {
        const val MAIN_ID = "main"
    }
}
