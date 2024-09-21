package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

@Composable
fun NavigationRouter(
    id: String,
    initial: Route,
    content: @Composable (PersistentList<NavigationEntry>) -> Unit,
) {
    // Find the top-level router and link the entry's lifecycle
    // to it, so if the top level gets destroyed we also get
    // destroyed.
    val f = LocalNavigationNodeLogicalStack.current.last()
    val parentScope = f.scope

    val navStackPrefix = id
    val navPile = f.getOrCreate(id) {
        val entry = NavigationEntryImpl(
            source = "router root",
            id = id,
            parent = parentScope,
            route = initial,
        )
        NavigationStack(
            id = NavigationStack.createId(
                prefix = navStackPrefix,
                suffix = NavigationStack.createIdSuffix(initial),
            ),
            entry = entry,
        )
    }

    val canPop = remember(navPile) {
        snapshotFlow { navPile.value }
            .flatMapLatest { pile ->
                val stack = pile.lastOrNull()
                val entry = stack?.value?.lastOrNull()
                if (entry != null) {
                    return@flatMapLatest entry
                        .activeBackPressInterceptorsStateFlow
                        .map { interceptors ->
                            interceptors.isNotEmpty() || stack.value.size > 1 || pile.size > 1
                        }
                }

                flowOf(false)
            }
    }
    NavigationController(
        canPop = canPop,
        handle = { intent ->
            val primaryNavStack = navPile.value.lastOrNull()
                ?.toImmutableModel()
            // Should never happen
                ?: return@NavigationController NavigationIntent.Pop

            // If the navigation intent is a simple pop, then give it to
            // the back press interceptors first and only then adjust the
            // navigation stack.
            if (intent is NavigationIntent.Pop) {
                val backPressInterceptorRegistration = primaryNavStack
                    .entries
                    .asReversed()
                    .firstNotNullOfOrNull { navEntry ->
                        val backPressInterceptors =
                            navEntry.activeBackPressInterceptorsStateFlow.value
                        backPressInterceptors.values.firstOrNull()
                    }
                if (backPressInterceptorRegistration != null) {
                    backPressInterceptorRegistration.block()
                    return@NavigationController null
                }
            }

            val scope = object : NavigationIntentScope {
                override val backStack: NavigationBackStack = primaryNavStack

                override fun getStack(id: String) = kotlin.run {
                    val navStackId = NavigationStack.createId(
                        prefix = navStackPrefix,
                        suffix = id,
                    )
                    val navStack = navPile.value.firstOrNull { it.id == navStackId }
                        ?: return@run NavigationBackStack(
                            id = navStackId,
                            entries = persistentListOf(),
                        )
                    navStack.toImmutableModel()
                }
            }

            val newNavStack = scope
                .exec(
                    intent = intent,
                    scope = parentScope,
                )
            if (newNavStack == null) {
                // The intent was not handled, pass it to the next
                // navigation controller.
                return@NavigationController intent
            }

            // Reorder navigation pile
            val newNavPile = run {
                // Modify existing navigation stack, this is
                // needed to maintain the lifecycle of the
                // navigation entries.
                val existingNavStack = run {
                    navPile.value.firstOrNull { it.id == newNavStack.id }
                        ?.apply {
                            // Change the values of the existing stack
                            value = newNavStack.entries
                        }
                        ?: NavigationStack(
                            id = newNavStack.id,
                            entries = newNavStack.entries,
                        )
                }

                navPile.value
                    .removeAll { it.id == newNavStack.id }
                    .add(existingNavStack)
                    .removeAll { it.value.isEmpty() }
            }

            when {
                newNavPile.isEmpty() -> {
                    NavigationIntent.Pop
                }

                else -> {
                    navPile.value = newNavPile
                    // We have handled the navigation intent.
                    null
                }
            }
        },
    ) { controller ->
        val navStack = navPile.value.lastOrNull()
            ?: return@NavigationController

        val localBackStack = navStack.value
        val globalBackStack = LocalNavigationNodeLogicalStack.current.addAll(localBackStack)
        val backHandler = LocalNavigationBackHandler.current

        DisposableEffect(
            controller,
            globalBackStack,
            backHandler,
        ) {
            val registration = backHandler.register(controller, globalBackStack)
            onDispose {
                registration()
            }
        }

        CompositionLocalProvider(
            LocalNavigationRouter provides navStack,
        ) {
            content(localBackStack)
        }
    }
}

class NavigationPile(
    stack: NavigationStack,
) {
    private val _state = kotlin.run {
        val initialState = persistentListOf(stack)
        mutableStateOf(initialState)
    }

    var value: PersistentList<NavigationStack>
        get() = _state.value
        set(value) {
            val oldValue = _state.value

            val removedItems = mutableListOf<NavigationStack>()
            oldValue.forEach { e ->
                val exists = value.any { it.id === e.id }
                if (!exists) {
                    removedItems += e
                }
            }
            removedItems.forEach { stack ->
                stack.value.forEach { entry ->
                    entry.destroy()
                }
            }
            _state.value = value
        }
}

class NavigationStack(
    val id: String,
    entries: PersistentList<NavigationEntry>,
) {
    companion object {
        fun createId(
            prefix: String,
            suffix: String,
        ) = "$prefix|$suffix"

        fun createIdSuffix(route: Route) = route::class.qualifiedName.orEmpty()
    }

    private val _state = mutableStateOf(entries)

    var value: PersistentList<NavigationEntry>
        get() = _state.value
        set(value) {
            val oldValue = _state.value

            val removedItems = mutableListOf<NavigationEntry>()
            oldValue.forEach { e ->
                val exists = value.any { it === e }
                if (!exists) {
                    removedItems += e
                }
            }
            removedItems.forEach { entry ->
                entry.destroy()
            }
            _state.value = value
        }

    constructor(
        id: String,
        entry: NavigationEntry,
    ) : this(
        id = id,
        entries = persistentListOf(entry),
    )

    fun toImmutableModel() = NavigationBackStack(
        id = id,
        entries = value,
    )
}

private fun NavigationIntentScope.exec(
    intent: NavigationIntent,
    scope: CoroutineScope,
): NavigationBackStack? = when (intent) {
    is NavigationIntent.Composite -> run compose@{
        var ns = this
        for (subIntent in intent.list) {
            val new = ns.exec(
                intent = subIntent,
                scope = scope,
            ) ?: return@compose null
            ns = object : NavigationIntentScope {
                override val backStack: NavigationBackStack
                    get() = new

                override fun getStack(id: String) = this@exec.getStack(id)
            }
        }
        ns.backStack
    }

    is NavigationIntent.NavigateToRoute -> kotlin.run {
        val entries = when (intent.launchMode) {
            NavigationIntent.NavigateToRoute.LaunchMode.DEFAULT -> backStack.entries
            NavigationIntent.NavigateToRoute.LaunchMode.SINGLE -> {
                val clearedBackStack =
                    backStack.entries.removeAll { it.route::class == intent.route::class }
                val existingEntry = backStack.entries.lastOrNull { it.route == intent.route }
                if (existingEntry != null) {
                    // Fast path if existing route matches the new route.
                    return@run backStack.copy(
                        entries = clearedBackStack.add(existingEntry),
                    )
                }

                clearedBackStack
            }
        }

        val r = intent.route
        val e = NavigationEntryImpl(
            source = "router",
            id = Uuid.random().toString(),
            parent = scope,
            route = r,
        )
        backStack.copy(
            entries = entries.add(e),
        )
    }

    is NavigationIntent.SetRoute -> {
        val r = intent.route
        val e = NavigationEntryImpl(
            source = "router",
            id = Uuid.random().toString(),
            parent = scope,
            route = r,
        )
        backStack.copy(
            entries = persistentListOf(e),
        )
    }

    is NavigationIntent.NavigateToStack -> {
        backStack.copy(
            entries = intent.stack.toPersistentList(),
        )
    }

    is NavigationIntent.Pop -> {
        val entries = backStack.entries
        if (entries.size > 0) {
            val newEntries = entries.removeAt(entries.size - 1)
            backStack.copy(entries = newEntries)
        } else {
            null
        }
    }

    is NavigationIntent.PopById -> run {
        val newEntries = backStack.entries.popById(intent.id, intent.exclusive)
            ?: return@run null
        backStack.copy(entries = newEntries)
    }

    is NavigationIntent.Manual -> {
        val factory = fun(route: Route): NavigationEntry =
            NavigationEntryImpl(
                source = "router",
                id = Uuid.random().toString(),
                parent = scope,
                route = route,
            )
        intent.handle(this, factory)
    }

    else -> null
}

fun PersistentList<NavigationEntry>.popById(
    id: String,
    exclusive: Boolean,
) = kotlin.run {
    val i = indexOfLast { it.id == id }
    if (i >= 0) {
        val offset = if (exclusive) 1 else 0
        subList(0, i + offset)
            .toPersistentList()
    } else {
        null
    }
}

internal val LocalNavigationRouter = compositionLocalOf<NavigationStack> {
    TODO()
}
