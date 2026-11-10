package com.artemchep.keyguard.feature.navigation

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
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.WindowId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The lifecycle of the entries of a [NavigationPile]: what it creates,
 * when it destroys, and what a restored pile starts with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// One function per scenario.
@Suppress("TooManyFunctions")
class NavigationPileTest {
    private val hostJob = SupervisorJob()
    private val hostScope = CoroutineScope(hostJob + Dispatchers.Unconfined)

    /** The entries of a pile are children of the host job. */
    private val liveChildren: Int
        get() = hostJob.children.count()

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
    fun `a composite that goes to the parent creates no entry`() {
        val pile = pile()
        val before = liveChildren
        val value = pile.value

        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.NavigateToRoute(ItemRoute("a")),
                NavigationIntent.NavigateToRoute(ItemRoute("b")),
                NavigationIntent.NavigateToBrowser("https://example.com"),
            ),
        )
        assertSame(intent, pile.dispatch(intent))

        assertEquals(before, liveChildren)
        assertSame(value, pile.value)
    }

    @Test
    fun `a reduction that empties the router creates no entry and changes nothing`() {
        val pile = pile()
        val root = pile.entry(ROUTER_ID)
        val before = liveChildren
        val value = pile.value

        // The new entry exists only in the discarded result.
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.NavigateToRoute(ItemRoute("a")),
                NavigationIntent.PopById(ROUTER_ID, exclusive = false),
            ),
        )
        assertEquals(NavigationIntent.Pop, pile.dispatch(intent))

        assertEquals(before, liveChildren)
        assertSame(value, pile.value)
        assertSame(root, pile.entry(ROUTER_ID))
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `a popped entry leaves nothing behind in the parent job`() {
        val pile = pile()
        val before = liveChildren

        assertNull(pile.dispatch(NavigationIntent.NavigateToRoute(ItemRoute("a"))))
        val entry = pile.value.last().value.last()
        assertEquals(before + 1, liveChildren)

        assertNull(pile.dispatch(NavigationIntent.Pop))

        assertTrue(entry.isDestroyed)
        assertEquals(before, liveChildren)
    }

    @Test
    fun `an entry replaced by a new one with the same id is destroyed`() {
        val pile = pile()
        val oldRoot = pile.entry(ROUTER_ID)
        val before = liveChildren

        assertNull(pile.dispatch(NavigationIntent.ReplaceRoot(ItemRoute("new root"))))

        val newRoot = pile.entry(ROUTER_ID)
        assertNotSame(oldRoot, newRoot)
        assertTrue(oldRoot.isDestroyed)
        assertFalse(newRoot.isDestroyed)
        assertEquals(before, liveChildren)
    }

    @Test
    fun `a root replaced inside a composite gets no entry`() {
        val pile = pile()
        val root = pile.entry(ROUTER_ID)
        val before = liveChildren

        // The root that NavigateToStack creates exists only in the composite.
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.NavigateToStack("x", ItemRoute("x")),
                NavigationIntent.ReplaceRoot(ItemRoute("y")),
            ),
        )
        assertNull(pile.dispatch(intent))

        assertEquals(before + 1, liveChildren)
        assertEquals(listOf(ItemRoute("y")), pile.value.last().value.map { it.route })
        assertSame(root, pile.value.first().value.single())
        assertFalse(root.isDestroyed)
    }

    @Test
    fun `entries that leave are destroyed after the new stacks are published`() {
        val pile = pile()
        assertNull(pile.dispatch(NavigationIntent.NavigateToRoute(ItemRoute("a"))))
        val a = pile.value.last().value.last()
        var idsOnDestroy: List<String>? = null
        a.scope.coroutineContext[Job]!!.invokeOnCompletion {
            idsOnDestroy = pile.value.last().value.map { it.id }
        }

        assertNull(pile.dispatch(NavigationIntent.Pop))

        assertEquals(listOf(ROUTER_ID), idsOnDestroy)
    }

    @Test
    fun `a restored pile keeps the ids and routes of the last stack only`() {
        val saved = pile()
        assertNull(saved.dispatch(NavigationIntent.NavigateToStack("x", ItemRoute("x"))))
        assertNull(saved.dispatch(NavigationIntent.NavigateToRoute(ItemRoute("y"))))
        assertEquals(2, saved.value.size)
        val savedStack = saved.value.last()

        val newHostJob = SupervisorJob(parent = hostJob)
        val restored = restoreNavigationPile(
            saved = saved,
            id = "restored",
            routerId = ROUTER_ID,
            parentScope = CoroutineScope(newHostJob + Dispatchers.Unconfined),
        )

        assertNotNull(restored)
        assertEquals(listOf(savedStack.id), restored.value.map { it.id })
        val entries = restored.value.single().value
        assertEquals(savedStack.value.map { it.id }, entries.map { it.id })
        assertEquals(savedStack.value.map { it.route }, entries.map { it.route })
        savedStack.value.zip(entries).forEach { (old, new) -> assertNotSame(old, new) }
        // No entry exists for the stacks that did not come back.
        assertEquals(entries.size, newHostJob.children.count())
    }

    @Test
    fun `a restored entry starts with the persisted state the saved entry had at restore time`() {
        val saved = pile()
        val savedRoot = saved.entry(ROUTER_ID)
        savedRoot.query().value = "saved"

        val restored = restoreNavigationPile(
            saved = saved,
            id = "restored",
            routerId = ROUTER_ID,
            parentScope = hostScope,
        )
        savedRoot.query().value = "changed after restore"

        assertNotNull(restored)
        assertEquals("saved", restored.entry(ROUTER_ID).query().value)
    }

    private fun pile(): NavigationPile = NavigationPile(
        id = "pile",
        routerId = ROUTER_ID,
        parentScope = hostScope,
        initial = NavigationPileState(
            stacks = persistentListOf(
                NavigationStackState(
                    id = NavigationStack.createId(
                        prefix = ROUTER_ID,
                        suffix = NavigationStack.createIdSuffix(RootRoute),
                    ),
                    entries = persistentListOf(
                        NavigationEntryConfig(
                            id = ROUTER_ID,
                            route = RootRoute,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun NavigationPile.entry(id: String): NavigationEntry = value
        .flatMap { it.value }
        .single { it.id == id }

    /** A persisted field of the entry's screen state. */
    private fun NavigationEntry.query(): MutableStateFlow<String> = vm.getOrPut(
        key = FIELD_KEY,
        sessionId = null,
        c = object : NavigationController {
            override val scope = hostScope
            override fun queue(intent: NavigationIntent) = Unit
            override fun canPop() = flowOf(false)
        },
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
            override val coroutineContext = hostScope.coroutineContext
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
        const val FIELD_KEY = "navigation-entry:screen"
    }
}
