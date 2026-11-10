package com.artemchep.keyguard.feature.navigation

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The id and route of one entry of a router. The router keeps one live
 * entry per configuration, so configurations are equal only by identity:
 * a new configuration that reuses an id is a new entry.
 */
internal class NavigationEntryConfig(
    val id: String,
    val route: Route,
) {
    init {
        require('/' !in id)
    }

    override fun toString(): String = "NavigationEntryConfig(id=$id, route=$route)"
}

/**
 * The stacks of a router. The active stack is the last one.
 */
internal data class NavigationPileState(
    val stacks: PersistentList<NavigationStackState>,
)

internal data class NavigationStackState(
    val id: String,
    val entries: PersistentList<NavigationEntryConfig>,
)

internal sealed interface NavigationReduction {
    data class Handled(
        val pile: NavigationPileState,
    ) : NavigationReduction

    /** The router does not handle the intent, it goes to the parent controller. */
    data object Unhandled : NavigationReduction

    /** The router would be empty, the parent controller gets a pop. */
    data object PopParent : NavigationReduction
}

/**
 * Applies the [intent] to the stacks of a router with the [routerId].
 * It creates configurations only, the caller creates their entries
 * once it commits the result.
 */
internal fun NavigationPileState.reduce(
    routerId: String,
    intent: NavigationIntent,
    newId: () -> String,
): NavigationReduction {
    val activeStack = stacks.lastOrNull()
        ?: return NavigationReduction.PopParent

    val newStack = exec(
        stack = activeStack,
        intent = intent,
        routerId = routerId,
        newId = newId,
    )
    val newStacks = newStack?.let { stack ->
        // The touched stack becomes the active one.
        stacks
            .removeAll { it.id == stack.id }
            .add(stack)
            .removeAll { it.entries.isEmpty() }
    }
    return when {
        newStacks == null -> NavigationReduction.Unhandled
        newStacks.isEmpty() -> NavigationReduction.PopParent
        else -> NavigationReduction.Handled(
            pile = NavigationPileState(newStacks),
        )
    }
}

/**
 * Applies the [intent] to the [stack] being built. The receiver is the
 * pile from before the intent. Returns `null` if the router does not
 * handle the intent.
 */
private fun NavigationPileState.exec(
    stack: NavigationStackState,
    intent: NavigationIntent,
    routerId: String,
    newId: () -> String,
): NavigationStackState? = when (intent) {
    is NavigationIntent.Composite -> execComposite(stack, intent, routerId, newId)
    is NavigationIntent.NavigateToRoute -> execNavigateToRoute(stack, intent, newId)
    is NavigationIntent.SetRoute -> execSetRoute(stack, intent, newId)
    is NavigationIntent.NavigateToStack -> execNavigateToStack(intent, routerId)
    is NavigationIntent.ReplaceRoot -> execReplaceRoot(stack, intent)
    is NavigationIntent.Pop -> execPop(stack)
    is NavigationIntent.PopById -> execPopById(stack, intent)
    else -> null
}

private fun NavigationPileState.execComposite(
    stack: NavigationStackState,
    intent: NavigationIntent.Composite,
    routerId: String,
    newId: () -> String,
): NavigationStackState? {
    var newStack = stack
    for (subIntent in intent.list) {
        // Each intent continues on the stack of the previous one,
        // but reads the other stacks from before the composite.
        newStack = exec(
            stack = newStack,
            intent = subIntent,
            routerId = routerId,
            newId = newId,
        ) ?: return null
    }
    return newStack
}

private fun execNavigateToRoute(
    stack: NavigationStackState,
    intent: NavigationIntent.NavigateToRoute,
    newId: () -> String,
): NavigationStackState {
    val entries = when (intent.launchMode) {
        NavigationIntent.NavigateToRoute.LaunchMode.DEFAULT -> stack.entries
        NavigationIntent.NavigateToRoute.LaunchMode.SINGLE -> {
            val clearedBackStack =
                stack.entries.removeAll { it.route::class == intent.route::class }
            val existingEntry = stack.entries.lastOrNull { it.route == intent.route }
            if (existingEntry != null) {
                // Fast path if existing route matches the new route.
                return stack.copy(
                    entries = clearedBackStack.add(existingEntry),
                )
            }

            clearedBackStack
        }
    }

    val config = NavigationEntryConfig(
        id = newId(),
        route = intent.route,
    )
    return stack.copy(
        entries = entries.add(config),
    )
}

private fun execSetRoute(
    stack: NavigationStackState,
    intent: NavigationIntent.SetRoute,
    newId: () -> String,
): NavigationStackState {
    val config = NavigationEntryConfig(
        id = newId(),
        route = intent.route,
    )
    return stack.copy(
        entries = persistentListOf(config),
    )
}

/**
 * Reads the target stack from the receiver, the pile from before the
 * intent. Inside a composite that is the pile from before the composite.
 */
private fun NavigationPileState.execNavigateToStack(
    intent: NavigationIntent.NavigateToStack,
    routerId: String,
): NavigationStackState {
    val id = NavigationStack.createId(
        prefix = routerId,
        suffix = intent.id,
    )
    val stack = stacks.firstOrNull { it.id == id }
    val rootRoute = stack?.entries?.firstOrNull()?.route
    if (stack != null && rootRoute?.descriptor == intent.root.descriptor) {
        // The stack still starts at the same destination.
        return stack
    }

    val config = NavigationEntryConfig(
        id = routerId,
        route = intent.root,
    )
    return NavigationStackState(
        id = id,
        entries = persistentListOf(config),
    )
}

private fun execReplaceRoot(
    stack: NavigationStackState,
    intent: NavigationIntent.ReplaceRoot,
): NavigationStackState? {
    val root = stack.entries.firstOrNull()
        ?: return null
    val config = NavigationEntryConfig(
        id = root.id,
        route = intent.route,
    )
    return stack.copy(
        entries = stack.entries.set(0, config),
    )
}

private fun execPop(
    stack: NavigationStackState,
): NavigationStackState? {
    val entries = stack.entries
    return if (entries.isNotEmpty()) {
        val newEntries = entries.removeAt(entries.size - 1)
        stack.copy(entries = newEntries)
    } else {
        null
    }
}

private fun execPopById(
    stack: NavigationStackState,
    intent: NavigationIntent.PopById,
): NavigationStackState? {
    val entries = stack.entries
    val i = entries.indexOfLast { it.id == intent.id }
    return if (i >= 0) {
        val offset = if (intent.exclusive) 1 else 0
        val newEntries = entries
            .subList(0, i + offset)
            .toPersistentList()
        stack.copy(entries = newEntries)
    } else {
        null
    }
}
