package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Root-rendered dialogs must not deliver results to a dismissed or locked presentation. */
internal fun CoroutineScope.guardNavigation(
    intercept: (NavigationIntent) -> Boolean,
): (NavigationIntent) -> Boolean = { intent ->
    // Consume late navigation instead of presenting a dialog for an expired producer.
    !isActive || intercept(intent.guardResults { isActive })
}

/**
 * Claims the producer's own pop and runs [complete] on [mainScope] while this
 * producer is still active; the pop is dispatched from the background pipeline.
 */
internal fun CoroutineScope.completeOnPop(
    mainScope: CoroutineScope,
    complete: () -> Unit,
): (NavigationIntent) -> Boolean = { intent ->
    val pop = intent is NavigationIntent.Pop || intent is NavigationIntent.PopById
    if (pop) launchOnMainWhileActive(mainScope, complete)
    pop
}

/** Runs [block] on [mainScope] unless this producer has ended by the time it gets there. */
internal fun CoroutineScope.launchOnMainWhileActive(mainScope: CoroutineScope, block: () -> Unit) {
    val producerScope = this
    mainScope.launch { if (producerScope.isActive) block() }
}

private fun NavigationIntent.guardResults(isActive: () -> Boolean): NavigationIntent = when (this) {
    is NavigationIntent.Composite -> copy(list = list.map { it.guardResults(isActive) })
    is NavigationIntent.NavigateToRoute -> {
        // The receiver and its inner route have the same result type; only forward that value unchanged.
        @Suppress("UNCHECKED_CAST")
        val receiver = route as? RouteResultReceiver<Any?>
        if (receiver == null) {
            this
        } else {
            val deliver: (Any?) -> Unit = { if (isActive()) receiver.resultTransmitter(it) }
            val inner = receiver.innerRoute
            // The smart cast picks the dialog overload, so a dialog route stays a dialog.
            val guarded = if (inner is DialogRouteForResult<Any?>) {
                registerRouteResultReceiver(inner, deliver)
            } else {
                registerRouteResultReceiver(inner, deliver)
            }
            copy(route = guarded)
        }
    }
    else -> this
}
