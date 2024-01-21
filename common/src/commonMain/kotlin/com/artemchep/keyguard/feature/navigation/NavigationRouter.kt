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
import java.util.UUID

@Composable
fun NavigationRouter(
    id: String,
    initial: Route,
    content: @Composable (PersistentList<NavigationEntry>) -> Unit,
) {
    // Fid the top-level router and link the entry's lifecycle
    // to it, so if the top level gets destroyed we also get
    // destroyed.
    val f = LocalNavigationNodeLogicalStack.current.last()
    val parentScope = f.scope
    val navStack = remember(id) {
        val navEntry = NavigationEntryImpl(
            source = "router root",
            id = id,
            parent = parentScope,
            route = initial,
        )
        NavigationStack(navEntry)
    }
    val canPop = remember(navStack) {
        snapshotFlow { navStack.value }
            .flatMapLatest { stack ->
                val navEntry = stack.lastOrNull()
                if (navEntry != null) {
                    navEntry
                        .activeBackPressInterceptorsStateFlow
                        .map { interceptors ->
                            interceptors.isNotEmpty() || stack.size > 1
                        }
                } else {
                    flowOf(false)
                }
            }
    }
    NavigationController(
        canPop = canPop,
        handle = { intent ->
            val backStack = navStack.value

            // If the navigation intent is a simple pop, then give it to
            // the back press interceptors first and only then adjust the
            // navigation stack.
            if (intent is NavigationIntent.Pop) {
                val backPressInterceptorRegistration = backStack
                    .asReversed()
                    .firstNotNullOfOrNull { navEntry ->
                        val backPressInterceptors = navEntry.activeBackPressInterceptorsStateFlow.value
                        backPressInterceptors.values.firstOrNull()
                    }
                if (backPressInterceptorRegistration != null) {
                    backPressInterceptorRegistration.block()
                    return@NavigationController null
                }
            }

            val newBackStack = backStack
                .exec(
                    intent = intent,
                    scope = parentScope,
                )
            when {
                newBackStack == null -> {
                    // The intent was not handled, pass it to the next
                    // navigation controller.
                    return@NavigationController intent
                }

                newBackStack.isEmpty() -> {
                    NavigationIntent.Pop
                }

                else -> {
                    navStack.value = newBackStack
                    // We have handled the navigation intent.
                    null
                }
            }
        },
    ) { controller ->
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

class NavigationStack(
    entry: NavigationEntry,
) {
    private val _state = kotlin.run {
        val initialState = persistentListOf(entry)
        mutableStateOf(initialState)
    }

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
            removedItems.forEach {
                it.destroy()
            }
            _state.value = value
        }
}

private fun PersistentList<NavigationEntry>.exec(
    intent: NavigationIntent,
    scope: CoroutineScope,
): PersistentList<NavigationEntry>? = when (intent) {
    is NavigationIntent.Composite -> run compose@{
        var backStack = this
        for (subIntent in intent.list) {
            val new = backStack.exec(
                intent = subIntent,
                scope = scope,
            )
            backStack = new
                ?: return@compose null
        }
        backStack
    }

    is NavigationIntent.NavigateToRoute -> kotlin.run {
        val backStack = when (intent.launchMode) {
            NavigationIntent.NavigateToRoute.LaunchMode.DEFAULT -> this
            NavigationIntent.NavigateToRoute.LaunchMode.SINGLE -> {
                val clearedBackStack = removeAll { it.route::class == intent.route::class }
                val existingEntry = lastOrNull { it.route == intent.route }
                if (existingEntry != null) {
                    // Fast path if existing route matches the new route.
                    return@run clearedBackStack.add(existingEntry)
                }

                clearedBackStack
            }
        }

        val r = intent.route
        val e = NavigationEntryImpl(
            source = "router",
            id = UUID.randomUUID().toString(),
            parent = scope,
            route = r,
        )
        backStack.add(e)
    }

    is NavigationIntent.SetRoute -> {
        val r = intent.route
        val e = NavigationEntryImpl(
            source = "router",
            id = UUID.randomUUID().toString(),
            parent = scope,
            route = r,
        )
        persistentListOf(e)
    }

    is NavigationIntent.NavigateToStack -> intent.stack.toPersistentList()
    is NavigationIntent.Pop -> {
        if (size > 0) {
            removeAt(size - 1)
        } else {
            null
        }
    }

    is NavigationIntent.PopById -> popById(intent.id, intent.exclusive)
    is NavigationIntent.Manual -> {
        val factory = fun(route: Route): NavigationEntry =
            NavigationEntryImpl(
                source = "router",
                id = UUID.randomUUID().toString(),
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
