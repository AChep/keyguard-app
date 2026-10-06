package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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

    protected fun pile(vararg entries: NavigationEntry) = NavigationPile(
        id = "test",
        stack = NavigationStack("test", entries.toList().toPersistentList()),
    )

    protected fun controller(onBack: () -> Unit = {}) = RecordingController(scope, onBack)

    protected class RecordingController(
        override val scope: CoroutineScope,
        val onBack: () -> Unit,
    ) : NavigationController {
        val intents = mutableListOf<NavigationIntent>()

        override fun queue(intent: NavigationIntent) {
            intents += intent
            onBack()
        }

        // Deliberately stale: keyboard navigation must use the live local guard.
        override fun canPop() = flowOf(true)
    }

    private object TestRoute : Route {
        @Composable
        override fun Content() = Unit
    }

    protected object TestDialogRoute : DialogRoute {
        @Composable
        override fun Content() = Unit
    }
}
