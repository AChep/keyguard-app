package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

@OptIn(ExperimentalCoroutinesApi::class, InternalComposeUiApi::class)
abstract class NavigationBackTestFixture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    protected val escape = KeyEvent(Key.Escape, KeyEventType.KeyDown)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    protected fun entry(id: String, route: Route = TestRoute) =
        NavigationEntryImpl("test", scope, id, route)

    /** A router with one stack. It creates the entries of the [items] itself. */
    protected fun pile(vararg items: Pair<String, Route>) = NavigationPile(
        id = "test",
        routerId = "test",
        parentScope = scope,
        initial = NavigationPileState(
            stacks = persistentListOf(
                NavigationStackState(
                    id = "test",
                    entries = items
                        .map { (id, route) -> NavigationEntryConfig(id, route) }
                        .toPersistentList(),
                ),
            ),
        ),
    )

    protected fun pile(vararg ids: String) = pile(*ids.map { it to TestRoute }.toTypedArray())

    /** A live entry of the pile. */
    protected fun NavigationPile.entry(id: String): NavigationEntry = value
        .flatMap { it.value }
        .single { it.id == id }

    protected fun controller(onBack: () -> Unit = {}) = RecordingController(scope, onBack)

    /**
     * Registers a router the way [NavigationRouter] does: its depth is the
     * size of the [backStack], and Back queues a pop on the [controller].
     */
    protected fun NavigationHost.registerRouter(
        controller: NavigationController,
        backStack: List<NavigationEntry>,
        canPop: () -> Boolean,
    ): () -> Unit = registerBackTarget(
        depth = { backStack.size },
        canPop = canPop,
        onBack = {
            controller.queue(NavigationIntent.Pop)
        },
    )

    protected class RecordingController(
        override val scope: CoroutineScope,
        val onBack: () -> Unit,
    ) : NavigationController {
        val intents = mutableListOf<NavigationIntent>()

        override fun queue(intent: NavigationIntent) {
            intents += intent
            onBack()
        }

        // Deliberately stale: the host must use the live local guard.
        override fun canPop() = flowOf(true)
    }

    protected object TestRoute : Route {
        @Composable
        override fun Content() = Unit
    }

    protected object TestDialogRoute : DialogRoute {
        @Composable
        override fun Content() = Unit
    }
}
