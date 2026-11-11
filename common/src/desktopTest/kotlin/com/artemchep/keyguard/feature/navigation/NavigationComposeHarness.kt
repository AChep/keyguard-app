package com.artemchep.keyguard.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.feature.home.ResponsiveLayout
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.platform.WindowId
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities
import kotlin.test.assertNotNull
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.dsl.module

/**
 * Runs the [block] on the Swing thread with a fresh harness that uses
 * the [navAnimation], and closes the harness after.
 */
internal fun withNavigationHarness(
    navAnimation: NavAnimation = NavAnimation.DISABLED,
    block: NavigationComposeHarness.() -> Unit,
) {
    try {
        SwingUtilities.invokeAndWait {
            val harness = NavigationComposeHarness(navAnimation)
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

internal class NavigationRouterProbe(
    private val hostEntry: NavigationEntry,
    val controller: NavigationController,
    val entries: List<NavigationEntry>,
    /** The Back gesture the content of the router sees. */
    val back: NavigationBackGesture?,
    /** [LocalNavigationNodeFinishing] in the content of the router. */
    val finishing: Boolean,
) {
    val pile: NavigationPile
        get() = hostEntry.subStacks.values.single()
}

/**
 * Records the screens of a [NavigationComposeHarness]. A screen name
 * must be unique within a test.
 */
internal class NavigationScreenProbe {
    /**
     * The screens composed now, with the value of
     * [LocalNavigationNodeFinishing] they see.
     */
    val composed = mutableMapOf<String, Boolean>()

    /** How many times each screen was composed from scratch. */
    val compositions = mutableMapOf<String, Int>()

    /** The Back calls of the screens that hold a Back target. */
    val backs = mutableListOf<String>()

    val coordinates = mutableMapOf<String, LayoutCoordinates>()

    /** Includes graphics-layer transforms, without clipping to the viewport. */
    fun bounds(name: String): Rect {
        val coordinates = assertNotNull(coordinates[name])
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
    }

    fun isLive(name: String): Boolean = composed[name] == false

    /** Composed, but leaving or previewed. */
    fun isFinishing(name: String): Boolean = composed[name] == true

    fun compositions(name: String): Int = compositions[name] ?: 0
}

/**
 * Composes one root node in a window with its own [host], the way the
 * platform hosts do.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class NavigationComposeHarness(
    navAnimation: NavAnimation = NavAnimation.DISABLED,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The transition animation setting. */
    val navAnimation = MutableStateFlow(navAnimation)

    private val koin = Koin().apply {
        loadModules(listOf(NavigationComposeTestModule(scope, this@NavigationComposeHarness.navAnimation).module))
    }
    val host = NavigationHost()
    private val store = NavigationStore()
    private val rootCanPop = flowOf(false)
    private val rootHandle: (NavigationIntent) -> NavigationIntent? = { it }

    var mainRoute by mutableStateOf<Route?>(null)

    val screens = NavigationScreenProbe()

    // Filled by the composition.
    private val routers = mutableMapOf<String, NavigationRouterProbe>()

    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    /**
     * Composes the [route] in a scene of the [width] and [height], in
     * pixels and dp alike. The default size is narrow, a two-pane host
     * shows one pane in it.
     */
    fun start(
        route: Route,
        width: Int = NARROW_WIDTH,
        height: Int = NARROW_HEIGHT,
    ) {
        mainRoute = route
        scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
        ) {
            Content()
        }
        frame()
    }

    /**
     * Renders enough frames for a navigation to settle. Each frame is
     * a second after the previous one, so every animation step ends.
     * Zero [frameNanos] settles the navigation without playing animations.
     */
    fun frame(frameNanos: Long = FRAME_NANOS) {
        repeat(FRAMES_TO_SETTLE) {
            render(frameNanos)
        }
    }

    /** Renders exactly one frame. */
    fun render(frameNanos: Long = FRAME_NANOS) {
        val scene = checkNotNull(scene)
        scene.render(frameTime).close()
        frameTime += frameNanos
    }

    /**
     * `true` if the scene has a node with the [text], for content
     * that is not a screen of the [screens] probe.
     */
    fun hasText(text: String): Boolean = checkNotNull(scene)
        .semanticsOwners
        .any { owner ->
            owner.unmergedRootSemanticsNode.hasText(text)
        }

    fun router(id: String): NavigationRouterProbe = assertNotNull(routers[id], "Router $id is not composed")

    fun screen(name: String): Route = Screen(name)

    /**
     * A screen that holds a Back target while it is live, the way the
     * router does. Its Back is recorded in [NavigationScreenProbe.backs].
     */
    fun backTargetScreen(name: String): Route = BackTargetScreen(name)

    /**
     * A router that renders its stack with the [content], a
     * [NavigationNode] by default.
     */
    fun routerHost(
        routerId: String,
        initial: Route,
        content: @Composable (PersistentList<NavigationEntry>) -> Unit = { entries ->
            NavigationNode(entries = entries)
        },
    ): Route = RouterHost(routerId, initial, content)

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

    @Composable
    private fun ScreenContent(name: String) {
        val finishing = LocalNavigationNodeFinishing.current
        DisposableEffect(Unit) {
            screens.compositions[name] = screens.compositions(name) + 1
            onDispose {
                screens.composed.remove(name)
                screens.coordinates.remove(name)
            }
        }
        SideEffect {
            screens.composed[name] = finishing
        }
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { screens.coordinates[name] = it },
        )
    }

    private inner class Screen(private val name: String) : Route {
        @Composable
        override fun Content() = ScreenContent(name)

        override fun toString() = "Screen($name)"
    }

    private inner class BackTargetScreen(private val name: String) : Route {
        @Composable
        override fun Content() {
            ScreenContent(name)
            val host = LocalNavigationHost.current
            val finishing = LocalNavigationNodeFinishing.current
            DisposableEffect(host, finishing) {
                if (finishing) {
                    return@DisposableEffect onDispose { }
                }
                val unregister = host.registerBackTarget(
                    depth = { LIVE_SCREEN_DEPTH },
                    canPop = { true },
                ) {
                    screens.backs += name
                }
                onDispose {
                    unregister()
                }
            }
        }

        override fun toString() = "BackTargetScreen($name)"
    }

    private inner class RouterHost(
        private val routerId: String,
        private val initial: Route,
        private val content: @Composable (PersistentList<NavigationEntry>) -> Unit,
    ) : Route {
        @Composable
        override fun Content() {
            val hostEntry = LocalNavigationEntry.current
            NavigationRouter(id = routerId, initial = initial) { entries ->
                val controller = LocalNavigationController.current
                val back = LocalNavigationBackGesture.current
                val finishing = LocalNavigationNodeFinishing.current
                SideEffect {
                    routers[routerId] = NavigationRouterProbe(hostEntry, controller, entries, back, finishing)
                }
                content(entries)
            }
        }

        override fun toString() = "RouterHost($routerId)"
    }

    companion object {
        /** A two-pane host shows one pane in a scene this narrow. */
        const val NARROW_WIDTH = 320
        const val NARROW_HEIGHT = 480

        /** A two-pane host shows two panes in a scene this wide. */
        const val WIDE_WIDTH = 1000
        const val WIDE_HEIGHT = 700

        private const val MAIN_ID = "main"

        /** Enough for a navigation or a gesture step to settle. */
        private const val FRAMES_TO_SETTLE = 3

        private const val FRAME_NANOS = 1_000_000_000L

        private const val LIVE_SCREEN_DEPTH = 100
    }
}

/**
 * A router whose content is [TwoPaneNavigationContent], the way the list
 * screens host their details. The size of the scene picks one or two panes.
 */
internal fun NavigationComposeHarness.twoPaneHost(
    routerId: String,
    initial: Route,
): Route = routerHost(routerId, initial) { entries ->
    // Provides the home layout the two-pane scaffold reads.
    ResponsiveLayout {
        TwoPaneNavigationContent(entries)
    }
}

private fun SemanticsNode.hasText(text: String): Boolean {
    val texts = config.getOrNull(SemanticsProperties.Text).orEmpty()
    return texts.any { it.text == text } ||
        children.any { it.hasText(text) }
}

private class NavigationComposeTestModule(
    scope: CoroutineScope,
    navAnimation: StateFlow<NavAnimation>,
) {
    private val allowTwoPanelLayout: StateFlow<Boolean> = MutableStateFlow(true)

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
                override fun invoke() = navAnimation
            }
        }
        // The size of the scene alone picks one or two panes.
        single<GetAllowTwoPanelLayoutInPortrait> {
            object : GetAllowTwoPanelLayoutInPortrait {
                override fun invoke() = allowTwoPanelLayout
            }
        }
        single<GetAllowTwoPanelLayoutInLandscape> {
            object : GetAllowTwoPanelLayoutInLandscape {
                override fun invoke() = allowTwoPanelLayout
            }
        }
    }
}
