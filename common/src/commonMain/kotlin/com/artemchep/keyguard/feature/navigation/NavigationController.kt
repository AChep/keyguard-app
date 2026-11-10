package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

private const val TAG = "Controller"

/**
 * Installs a controller that handles local intents and delegates leftovers.
 */
@Composable
fun NavigationController(
    scope: CoroutineScope? = null,
    canPop: Flow<Boolean>,
    handle: (NavigationIntent) -> NavigationIntent?,
    content: @Composable (NavigationController) -> Unit,
) {
    val finalTag = N.tag(TAG)

    val parent = LocalNavigationController.current
    val controller = remember(canPop, handle, parent) {
        createNavigationController(
            parent = parent,
            scope = scope,
            canPop = canPop,
            handle = handle,
        )
    }
    CompositionLocalProvider(
        LocalNavigationController provides controller,
    ) {
        content(controller)
    }
}

/**
 * Creates a controller that handles intents with [handle] and passes
 * leftovers to the [parent].
 *
 * The [executor] is used only if this controller starts a new chain;
 * otherwise the controller shares the executor of its parent.
 */
internal fun createNavigationController(
    parent: NavigationController,
    scope: CoroutineScope?,
    canPop: Flow<Boolean>,
    handle: (NavigationIntent) -> NavigationIntent?,
    executor: NavigationExecutor = MainNavigationExecutor,
): NavigationController = RelayNavigationController(
    parent = parent,
    scope = scope,
    canPop = canPop,
    handle = handle,
    executor = executor,
)

/**
 * A controller of a chain that shares one [NavigationRelay] and one
 * [NavigationExecutor]: those of the parent if the parent is one of these,
 * new ones otherwise.
 *
 * The chain handles intents on the executor's thread, one at a time. On
 * that thread an intent runs at once and is handled when [queue] returns,
 * unless it is queued from a handler or a destroy callback: then it runs
 * after the current one. From any other thread [queue] posts the intent to
 * the executor and returns at once. A leftover goes up the chain within the
 * same task, ahead of queued intents.
 */
private class RelayNavigationController(
    private val parent: NavigationController,
    scope: CoroutineScope?,
    canPop: Flow<Boolean>,
    private val handle: (NavigationIntent) -> NavigationIntent?,
    executor: NavigationExecutor,
) : NavigationController {
    private val relay: NavigationRelay =
        (parent as? RelayNavigationController)?.relay
            ?: NavigationRelay()

    private val executor: NavigationExecutor =
        (parent as? RelayNavigationController)?.executor
            ?: executor

    override val scope: CoroutineScope = scope ?: parent.scope

    private val combinedCanPop = combine(
        canPop,
        parent.canPop(),
    ) { a, b -> a || b }

    override fun queue(intent: NavigationIntent) {
        if (executor.isCurrent) {
            submit(intent)
        } else {
            executor.post(scope) {
                submit(intent)
            }
        }
    }

    private fun submit(intent: NavigationIntent) {
        relay.submit {
            dispatch(intent)
        }
    }

    /**
     * Handles the [intent] and passes the leftover up the chain
     * without going through the relay again.
     */
    private fun dispatch(intent: NavigationIntent) {
        val leftover = handle(intent)
            ?: return
        if (parent is RelayNavigationController) {
            parent.dispatch(leftover)
        } else {
            parent.queue(leftover)
        }
    }

    override fun canPop(): Flow<Boolean> = combinedCanPop
}

/**
 * Queues navigation commands and exposes whether this branch can handle back.
 */
interface NavigationController {
    val scope: CoroutineScope

    /**
     * Queues the [intent]. On the main thread the intent is handled before
     * this returns, unless the call is re-entrant. From another thread this
     * returns at once and the intent is handled later on the main thread,
     * in the order that thread queued its intents. A main-thread call does
     * not wait for intents that other threads have posted.
     */
    fun queue(intent: NavigationIntent)

    fun canPop(): Flow<Boolean>
}

val LocalNavigationController = staticCompositionLocalOf<NavigationController> {
    object : NavigationController {
        override val scope: CoroutineScope get() = GlobalScope

        override fun queue(intent: NavigationIntent) {
            // Do nothing.
        }

        override fun canPop(): Flow<Boolean> = flowOf(false)
    }
}
