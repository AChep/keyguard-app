package com.artemchep.keyguard.feature.navigation

import androidx.compose.material3.lightColorScheme
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
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.WindowId
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
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

/**
 * Locks and unlocks the vault the way `AppScreen` does: it saves every router
 * into the [NavigationStore], drops the "main" root node and composes it again.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NavigationLockRestoreTest {
    @Test
    fun `unlock restores the active stack with the same ids and routes`() = withHarness {
        start(routerHost("outer", screen("list")))
        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        frame()
        val before = router("outer").entries
        assertEquals("outer", before.first().id)
        assertEquals(2, before.size)

        lock()
        assertTrue(before.all { it.isDestroyed })
        unlock()

        val after = router("outer").entries
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.route }, after.map { it.route })
        before.zip(after).forEach { (old, new) -> assertNotSame(old, new) }
        assertFalse(after.any { it.isDestroyed })
    }

    @Test
    fun `unlock restores the persisted fields of an entry`() = withHarness {
        start(routerHost("outer", screen("list")))
        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("details")))
        frame()
        val before = router("outer").entries.last()
        before.query().value = "saved"

        lock()
        unlock()

        val after = router("outer").entries.last()
        assertEquals(before.id, after.id)
        assertNotSame(before, after)
        assertEquals("saved", after.query().value)
    }

    @Test
    fun `unlock restores only the last stack of a router`() = withHarness {
        start(routerHost("outer", screen("list")))
        val initialStackId = router("outer").stackIds.single()
        val otherStackId = NavigationStack.createId(prefix = "outer", suffix = "other")
        val intent = NavigationIntent.NavigateToStack("other", screen("other"))
        router("outer").controller.queue(intent)
        frame()
        assertEquals(listOf(initialStackId, otherStackId), router("outer").stackIds)
        val before = router("outer").entries

        lock()
        unlock()

        assertEquals(listOf(otherStackId), router("outer").stackIds)
        assertEquals(before.map { it.id }, router("outer").entries.map { it.id })
    }

    @Test
    fun `a nested router is restored when it composes again`() = withHarness {
        start(routerHost("outer", routerHost("nested", screen("nested list"))))
        router("nested").controller.queue(NavigationIntent.NavigateToRoute(screen("nested details")))
        frame()
        val before = router("nested").entries
        assertEquals("nested", before.first().id)
        assertEquals(2, before.size)
        // Cover the nested router, so it stays out of the
        // composition after unlock.
        router("outer").controller.queue(NavigationIntent.NavigateToRoute(screen("outer details")))
        frame()

        lock()
        unlock()
        assertNull(routerOrNull("nested"))
        router("outer").controller.queue(NavigationIntent.Pop)
        frame()

        val after = router("nested").entries
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.route }, after.map { it.route })
        before.zip(after).forEach { (old, new) -> assertNotSame(old, new) }
        assertTrue(before.all { it.isDestroyed })
        assertFalse(after.any { it.isDestroyed })
    }

    @Test
    fun `a root node whose route changes under the same id gets a new entry`() = withHarness {
        val first = screen("first")
        val second = screen("second")
        start(first)
        val firstEntry = rendered("first")
        assertEquals(MAIN_ID, firstEntry.id)

        mainRoute = second
        frame()

        val secondEntry = rendered("second")
        assertEquals(MAIN_ID, secondEntry.id)
        assertSame(second, secondEntry.route)
        assertNotSame(firstEntry, secondEntry)
        assertTrue(firstEntry.isDestroyed)
        assertFalse(secondEntry.isDestroyed)
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
        private val host: NavigationEntry,
        val controller: NavigationController,
        val entries: List<NavigationEntry>,
    ) {
        /** The stack ids of the router, in order. The active stack is the last one. */
        val stackIds: List<String>
            get() = host.subStacks.values.single().value.map { it.id }
    }

    // One function per step the scenarios drive.
    @Suppress("TooManyFunctions")
    private class Harness {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val koin = Koin().apply {
            loadModules(listOf(TestModule(scope).module))
        }
        private val store = NavigationStore()
        private val navigationHost = NavigationHost()
        private val rootCanPop = flowOf(false)
        private val rootHandle: (NavigationIntent) -> NavigationIntent? = { it }

        private var unlocked by mutableStateOf(true)
        var mainRoute by mutableStateOf<Route?>(null)

        // Filled by the composition.
        private val routers = mutableMapOf<String, RouterProbe>()
        private val rendered = mutableMapOf<String, NavigationEntry>()

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

        /** What `AppScreen` does when the vault leaves the main state. */
        fun lock() {
            navigationHost.saveRouters(
                store = store,
            )
            unlocked = false
            routers.clear()
            rendered.clear()
            frame()
        }

        fun unlock() {
            unlocked = true
            frame()
        }

        fun router(id: String): RouterProbe = assertNotNull(routers[id], "Router $id is not composed")

        fun routerOrNull(id: String): RouterProbe? = routers[id]

        fun rendered(name: String): NavigationEntry = assertNotNull(rendered[name], "Screen $name is not composed")

        fun screen(name: String): Route = Screen(name)

        fun routerHost(routerId: String, initial: Route): Route = RouterHost(routerId, initial)

        /** A persisted field of the entry's screen state. */
        fun NavigationEntry.query(): MutableStateFlow<String> = vm.getOrPut(
            key = FIELD_KEY,
            sessionId = null,
            c = NoOpController(this@Harness.scope),
            showMessage = object : ShowMessage {
                override fun copy(value: ToastMessage, target: String?) = Unit
            },
            clipboardService = object : ClipboardService {
                override fun setPrimaryClip(value: String, concealed: Boolean) = Unit
                override fun clearPrimaryClip() = Unit
                override fun hasCopyNotification() = false
            },
            clipboardEventBus = ClipboardEventBus(),
            getScreenState = object : GetScreenState {
                override fun invoke(key: String) = flowOf(emptyMap<String, Any?>())
            },
            putScreenState = object : PutScreenState {
                override fun invoke(key: String, state: Map<String, Any?>): Nothing =
                    error("This test only uses navigation bundle persistence")
            },
            windowCoroutineScope = object : WindowCoroutineScope {
                override val coroutineContext = this@Harness.scope.coroutineContext
            },
            json = Json,
            screen = id,
            screenName = "screen",
            context = LeContext(),
            colorSchemeState = mutableStateOf(lightColorScheme()),
            windowIdState = mutableStateOf(WindowId(1)),
        ) {
            mutablePersistedFlow("query") { "initial" }
        }

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
                    NavigationHost(navigationHost) {
                        NavigationController(scope = scope, canPop = rootCanPop, handle = rootHandle) {
                            val route = mainRoute
                            if (unlocked && route != null) {
                                NavigationNode(id = MAIN_ID, route = route)
                            }
                        }
                    }
                }
            }
        }

        private inner class Screen(private val name: String) : Route {
            @Composable
            override fun Content() {
                val entry = LocalNavigationEntry.current
                SideEffect { rendered[name] = entry }
            }

            override fun toString() = "Screen($name)"
        }

        private inner class RouterHost(
            private val routerId: String,
            private val initial: Route,
        ) : Route {
            @Composable
            override fun Content() {
                val host = LocalNavigationEntry.current
                NavigationRouter(id = routerId, initial = initial) { entries ->
                    val controller = LocalNavigationController.current
                    SideEffect { routers[routerId] = RouterProbe(host, controller, entries) }
                    NavigationNode(entries = entries)
                }
            }

            override fun toString() = "RouterHost($routerId)"
        }
    }

    private class NoOpController(override val scope: CoroutineScope) : NavigationController {
        override fun queue(intent: NavigationIntent) = Unit
        override fun canPop() = flowOf(false)
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
        const val FIELD_KEY = "navigation-entry:screen"
    }
}
