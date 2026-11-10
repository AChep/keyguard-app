package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LocalWindowId
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

@Composable
fun NavigationRouter(
    id: String,
    initial: Route,
    // The suffix of the id of the initial stack, for routers that address their
    // stacks with NavigateToStack. Defaults to the class name of the initial route.
    initialStackId: String? = null,
    // Opt-in cross-process back-stack restore. Only stable-id top-level (section) routers
    // should enable it; the router [id] is the persistence key. Default off keeps every
    // existing router's behavior unchanged.
    persist: Boolean = false,
    content: @Composable (PersistentList<NavigationEntry>) -> Unit,
) {
    val store = LocalNavigationStore.current
    // Resolved for all routers (a cheap singleton) but only used when [persist] is true.
    val restoreRepo = koinInject<NavigationRestoreRepository>()

    // Find the top-level router and link the entry's lifecycle
    // to it, so if the top level gets destroyed we also get
    // destroyed.
    val stack = LocalNavigationNodeLogicalStack.current
    val f = stack.last()
    val parentScope = f.scope

    val navStackPrefix = id
    val navStackFullId = navigationNodeStack(
        logicalStack = stack,
    ) + "," + navStackPrefix
    val navPile = f.getOrCreate(navStackFullId) {
        val savedPile = store.pop(navStackFullId)
            ?.let { pile ->
                restoreNavigationPile(
                    saved = pile,
                    id = navStackFullId,
                    routerId = navStackPrefix,
                    parentScope = parentScope,
                )
            }
        if (savedPile != null) {
            return@getOrCreate savedPile
        }

        // Cross-process restore (opt-in): rebuild the stack from persisted descriptors.
        // All-or-nothing — if any entry cannot be reconstructed (a result route, or an
        // unmapped descriptor), fall through to the fresh initial route below.
        if (persist) {
            val restoredRoutes = restoreRepo.peek(id)?.map { it.toRoute() }
            if (!restoredRoutes.isNullOrEmpty() && restoredRoutes.all { it != null }) {
                val entries = restoredRoutes.filterNotNull().map { route ->
                    NavigationEntryConfig(
                        id = generateRouteId(route),
                        route = route,
                    )
                }
                return@getOrCreate NavigationPile(
                    id = navStackFullId,
                    routerId = navStackPrefix,
                    parentScope = parentScope,
                    initial = NavigationPileState(
                        stacks = persistentListOf(
                            NavigationStackState(
                                id = NavigationStack.createId(
                                    prefix = navStackPrefix,
                                    suffix = NavigationStack.createIdSuffix(entries.first().route),
                                ),
                                entries = entries.toPersistentList(),
                            ),
                        ),
                    ),
                )
            }
        }

        NavigationPile(
            id = navStackFullId,
            routerId = navStackPrefix,
            parentScope = parentScope,
            initial = initialNavigationPileState(
                routerId = navStackPrefix,
                initial = initial,
                initialStackId = initialStackId,
            ),
        )
    }

    if (persist) {
        // Persist the active stack's restorable descriptors on every change, keyed by [id].
        LaunchedEffect(navPile) {
            snapshotFlow {
                navPile.value.lastOrNull()?.value.orEmpty().map { it.route }
            }.collect { routes ->
                restoreRepo.save(id, routes.map { it.descriptor })
            }
        }
    }

    val host = LocalNavigationHost.current
    // The host saves the piles of its routers when the vault locks,
    // so an outgoing router stays attached too.
    DisposableEffect(host, f) {
        val detach = host.attachRouter(f)
        onDispose {
            detach()
        }
    }

    val keyboardShortcutsService = koinInject<KeyboardShortcutsServiceHost>()
    val windowId = LocalWindowId.current
    DisposableEffect(navPile, keyboardShortcutsService, windowId) {
        val unregister = keyboardShortcutsService.register(windowId) { keyEvent ->
            navPile.value
                .flatMap { it.value }
                .asReversed()
                .flatMap { navEntry ->
                    val keyEventInterceptors =
                        navEntry.activeKeyEventInterceptorsStateFlow.value
                    keyEventInterceptors.values
                }
                .firstOrNull { interceptor ->
                    interceptor.block(keyEvent)
                } != null
        }
        onDispose {
            unregister.invoke()
        }
    }

    val canPop = remember(navPile) {
        snapshotFlow { navPile.value.map { it.value } }
            .flatMapLatest { stacks ->
                // Re-check whenever the top route's interceptors change.
                stacks.lastOrNull()?.lastOrNull()
                    ?.activeBackPressInterceptorsStateFlow
                    ?: flowOf(null)
            }
            .map { navPile.canPop() }
            .distinctUntilChanged()
    }
    // Back interceptors change on other threads. The
    // collector brings the change to the main thread.
    LaunchedEffect(canPop, host) {
        canPop.collect {
            host.invalidateBack()
        }
    }
    NavigationController(
        canPop = canPop,
        handle = { intent ->
            navPile.dispatch(intent)
        },
    ) { controller ->
        val navStack = navPile.value.lastOrNull()
            ?: return@NavigationController

        val localBackStack = navStack.value
        val hostDepth = LocalNavigationNodeLogicalStack.current.size
        // An outgoing router (exit animation of this or a parent node) must not
        // receive Back, so it stays registered only while it is the live content.
        val finishing = LocalNavigationNodeFinishing.current

        DisposableEffect(
            host,
            controller,
            navPile,
            hostDepth,
            finishing,
        ) {
            if (finishing) {
                return@DisposableEffect onDispose { }
            }
            val unregister = host.registerBackTarget(
                // The deepest router that can pop gets Back.
                depth = { hostDepth + (navPile.value.lastOrNull()?.value?.size ?: 0) },
                canPop = navPile::canPop,
                onBack = {
                    controller.queue(NavigationIntent.Pop)
                },
            )
            // A commit changes what the router can pop
            // in the same call that changes the stack.
            val removeListener = navPile.addChangeListener {
                host.invalidateBack()
            }
            onDispose {
                removeListener()
                unregister()
            }
        }

        CompositionLocalProvider(
            LocalNavigationRouter provides navStack,
        ) {
            content(localBackStack)
        }
    }
}


internal fun NavigationPile.canPop(): Boolean {
    val stack = value.lastOrNull()?.value.orEmpty()
    val entry = stack.lastOrNull()
        ?.takeUnless { it.isDestroyed }
        ?: return false
    return entry.activeBackPressInterceptorsStateFlow.value.isNotEmpty() ||
        stack.size > 1 || value.size > 1
}

internal fun NavigationStack.interceptBackPress(): Boolean {
    // Only the top route gets to intercept Back. In a split layout, a search
    // or selection in the underlying list must not take Back from the detail pane.
    val interceptor = value.lastOrNull()
        ?.activeBackPressInterceptorsStateFlow?.value?.values?.firstOrNull()
        ?: return false
    interceptor.block()
    return true
}

/**
 * The stacks of a router that starts fresh: one stack with one entry for the
 * [initial] route, whose id is the [routerId]. The stack id ends with the
 * [initialStackId], or with the class name of the route if it is `null`.
 */
internal fun initialNavigationPileState(
    routerId: String,
    initial: Route,
    initialStackId: String?,
): NavigationPileState {
    val entry = NavigationEntryConfig(
        id = routerId,
        route = initial,
    )
    return NavigationPileState(
        stacks = persistentListOf(
            NavigationStackState(
                id = NavigationStack.createId(
                    prefix = routerId,
                    suffix = initialStackId ?: NavigationStack.createIdSuffix(initial),
                ),
                entries = persistentListOf(entry),
            ),
        ),
    )
}

/**
 * Builds the pile of a router from the [saved] pile the router had before
 * the vault locked. Only the active stack comes back. Its entries keep their
 * ids and routes, and start with the persisted state of the saved entries.
 */
internal fun restoreNavigationPile(
    saved: NavigationPile,
    id: String,
    routerId: String,
    parentScope: CoroutineScope,
): NavigationPile? {
    val savedStack = saved.value.lastOrNull()
        ?: return null
    val bundles = HashMap<NavigationEntryConfig, LeBundle>()
    val entries = savedStack.value.map { savedEntry ->
        val config = NavigationEntryConfig(
            id = savedEntry.id,
            route = savedEntry.route,
        )
        // Serialize all the data of the previous view models
        // and feed it back to the new ones.
        bundles[config] = savedEntry.vm.persistedState()
        config
    }
    return NavigationPile(
        id = id,
        routerId = routerId,
        parentScope = parentScope,
        initial = NavigationPileState(
            stacks = persistentListOf(
                NavigationStackState(
                    id = savedStack.id,
                    entries = entries.toPersistentList(),
                ),
            ),
        ),
        restoredState = bundles::get,
    )
}

/**
 * The stacks of one router and the live entries of their configurations.
 *
 * [dispatch] reduces an intent to a new state and commits it: it creates the
 * entries of new configurations, publishes the new stacks, destroys the
 * entries whose configuration left and then notifies the change listeners.
 * Configurations are compared by identity.
 */
class NavigationPile internal constructor(
    val id: String,
    private val routerId: String,
    /** The parent scope of the entries of this pile. */
    private val parentScope: CoroutineScope,
    initial: NavigationPileState,
    /** The state an initial entry starts with, if any. */
    restoredState: (NavigationEntryConfig) -> LeBundle? = { null },
) {
    private data class Committed(
        val state: NavigationPileState,
        val entries: Map<NavigationEntryConfig, NavigationEntryImpl>,
        val stacks: PersistentList<NavigationStack>,
    )

    private val _state = kotlin.run {
        val entries = createEntries(
            state = initial,
            previous = emptyMap(),
        ) { config ->
            // Before anything can read the view model.
            val bundle = restoredState(config)
            if (bundle != null) {
                vm.bundle = bundle
            }
        }
        val committed = committed(
            state = initial,
            entries = entries,
        )
        mutableStateOf(committed)
    }

    private val changeListeners = mutableListOf<() -> Unit>()

    val value: PersistentList<NavigationStack>
        get() = _state.value.stacks

    /**
     * Adds a [listener] that is called at the end of every commit, after
     * the entries that left are destroyed. Returns a function that removes it.
     */
    internal fun addChangeListener(listener: () -> Unit): () -> Unit {
        changeListeners += listener
        return {
            changeListeners.remove(listener)
        }
    }

    /**
     * Applies the [intent] to the stacks.
     *
     * Returns `null` if the router consumed the intent, otherwise the intent
     * to pass to the parent navigation controller.
     */
    internal fun dispatch(intent: NavigationIntent): NavigationIntent? {
        val old = _state.value
        val activeStack = old.stacks.lastOrNull()
        // Should never happen
            ?: return NavigationIntent.Pop

        // If the navigation intent is a simple pop, then give it to
        // the back press interceptors first and only then adjust the
        // navigation stack.
        val intercepted = intent is NavigationIntent.Pop &&
            activeStack.interceptBackPress()
        return if (intercepted) null else reduceAndCommit(old, intent)
    }

    private fun reduceAndCommit(
        old: Committed,
        intent: NavigationIntent,
    ): NavigationIntent? {
        val reduction = old.state.reduce(
            routerId = routerId,
            intent = intent,
            newId = { Uuid.random().toString() },
        )
        return when (reduction) {
            is NavigationReduction.Handled -> {
                commit(old, reduction.pile)
                // We have handled the navigation intent.
                null
            }

            // The intent was not handled, pass it to the next
            // navigation controller.
            NavigationReduction.Unhandled -> intent
            // The router stays as it is, the parent decides.
            NavigationReduction.PopParent -> NavigationIntent.Pop
        }
    }

    private fun commit(
        old: Committed,
        state: NavigationPileState,
    ) {
        val entries = createEntries(
            state = state,
            previous = old.entries,
        )
        _state.value = committed(
            state = state,
            entries = entries,
        )

        // Destroy the entries that left, later entries first.
        old.state.stacks.asReversed().forEach { stack ->
            stack.entries.asReversed().forEach { config ->
                if (config !in entries) {
                    old.entries[config]?.destroy()
                }
            }
        }

        changeListeners
            .toList()
            .forEach { listener ->
                listener()
            }
    }

    private fun createEntries(
        state: NavigationPileState,
        previous: Map<NavigationEntryConfig, NavigationEntryImpl>,
        onCreate: NavigationEntryImpl.(NavigationEntryConfig) -> Unit = {},
    ): Map<NavigationEntryConfig, NavigationEntryImpl> {
        val entries = HashMap<NavigationEntryConfig, NavigationEntryImpl>()
        state.stacks.forEach { stack ->
            stack.entries.forEach { config ->
                entries.getOrPut(config) {
                    previous[config]
                        ?: NavigationEntryImpl(
                            source = "router",
                            parent = parentScope,
                            config = config,
                        ).apply { onCreate(config) }
                }
            }
        }
        return entries
    }

    private fun committed(
        state: NavigationPileState,
        entries: Map<NavigationEntryConfig, NavigationEntryImpl>,
    ): Committed {
        val stacks = state.stacks
            .map { stack ->
                NavigationStack(
                    id = stack.id,
                    value = stack.entries
                        .map { config -> entries.getValue(config) }
                        .toPersistentList(),
                )
            }
            .toPersistentList()
        return Committed(
            state = state,
            entries = entries,
            stacks = stacks,
        )
    }

    override fun toString(): String {
        return "NavigationPile(id=$id, value=$value)"
    }
}

/**
 * A linear back stack rendered by [NavigationNode]. A snapshot: the pile
 * publishes a new one on every change.
 */
class NavigationStack internal constructor(
    val id: String,
    val value: PersistentList<NavigationEntry>,
) {
    companion object {
        fun createId(
            prefix: String,
            suffix: String,
        ) = "$prefix|$suffix"

        fun createIdSuffix(route: Route) = route::class.qualifiedName.orEmpty()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavigationStack) return false
        return id == other.id && value == other.value
    }

    override fun hashCode(): Int = 31 * id.hashCode() + value.hashCode()

    override fun toString(): String {
        return "NavigationStack(id=$id, value=$value)"
    }
}

private fun generateRouteId(route: Route): String {
    return Uuid.random().toString()
}

internal val LocalNavigationRouter = compositionLocalOf<NavigationStack> {
    TODO()
}
