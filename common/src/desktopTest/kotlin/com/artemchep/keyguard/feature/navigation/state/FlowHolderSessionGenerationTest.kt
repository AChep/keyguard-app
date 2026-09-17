package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class FlowHolderSessionGenerationTest {
    @Test
    fun `replacement cancels old producer while preserving persisted keys and fields`() = withFixture { fixture ->
        val first = fixture.producer("first", fixture.window())
        first.query.value = "saved search"
        assertSame(first, fixture.producer("first", fixture.window()))

        val second = fixture.producer("second", fixture.window())

        assertNotSame(first, second)
        assertFalse(first.scope.coroutineContext[Job]!!.isActive)
        assertTrue(second.scope.coroutineContext[Job]!!.isActive)
        assertNull(fixture.holder.getScopeOrNull(KEY, "first"))
        assertSame(second.scope, fixture.holder.getScopeOrNull(KEY, "second"))
        assertEquals("saved search", second.query.value)
        val persisted = fixture.holder.persistedState()
        assertEquals(setOf(KEY), persisted.map.keys)
        assertEquals("saved search", (persisted[KEY] as LeBundle)["$KEY:query"])
    }

    @Test
    fun `vault cancellation removes runtime state but retains navigation restoration`() = withFixture { fixture ->
        val window = fixture.window()
        val first = fixture.producer("first", window)
        first.query.value = "restored"

        window.cancel()

        assertFalse(first.scope.coroutineContext[Job]!!.isActive)
        assertTrue(fixture.navigationScope.coroutineContext[Job]!!.isActive)
        assertNull(fixture.holder.getScopeOrNull(KEY, "first"))
        assertEquals(setOf(KEY), fixture.holder.persistedState().map.keys)
        val restored = fixture.producer("second", fixture.window())
        assertEquals("restored", restored.query.value)

        fixture.entry.destroy()
        assertFalse(restored.scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `application screen follows navigation lifetime rather than a vault window`() = withFixture { fixture ->
        val window = fixture.window()
        val producer = fixture.producer(null, window)

        window.cancel()

        assertTrue(producer.scope.coroutineContext[Job]!!.isActive)
        fixture.entry.destroy()
        assertFalse(producer.scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `navigation cancellation stops vault producer before unrelated cleanup completes`() = withFixture { fixture ->
        val cleanup = CompletableDeferred<Unit>()
        val unrelated = fixture.navigationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        try {
            val producer = fixture.producer("first", fixture.window())

            fixture.navigationScope.cancel()

            assertFalse(unrelated.isCompleted)
            assertFalse(producer.scope.coroutineContext[Job]!!.isActive)
        } finally {
            cleanup.complete(Unit)
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.navigationScope.cancel()
            fixture.windows.forEach { it.cancel() }
            Dispatchers.resetMain()
        }
    }

    private class Fixture {
        val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entry = NavigationEntryImpl("test", navigationScope, "entry", TestRoute)
        val holder = entry.vm
        val windows = mutableListOf<WindowCoroutineScope>()
        private val controller = object : NavigationController {
            override val scope = navigationScope
            override fun queue(intent: NavigationIntent) = Unit
            override fun canPop() = flowOf(false)
        }

        fun window(): WindowCoroutineScope = object : WindowCoroutineScope {
            override val coroutineContext = SupervisorJob()
        }.also(windows::add)

        fun producer(sessionId: String?, window: WindowCoroutineScope): Producer = holder.getOrPut(
            key = KEY,
            sessionId = sessionId,
            c = controller,
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
            windowCoroutineScope = window,
            json = Json,
            screen = "entry",
            screenName = "vault",
            context = LeContext(),
            colorSchemeState = mutableStateOf(lightColorScheme()),
            windowIdState = mutableStateOf(WindowId(1)),
        ) {
            Producer(this, mutablePersistedFlow("query") { "initial" })
        }
    }

    private class Producer(val scope: RememberStateFlowScope, val query: MutableStateFlow<String>)

    private object TestRoute : Route {
        @Composable
        override fun Content() = Unit
    }

    private companion object {
        const val KEY = "navigation-entry:vault"
    }
}
