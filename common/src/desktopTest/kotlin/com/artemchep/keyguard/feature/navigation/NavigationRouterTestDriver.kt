package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Drives the intent handling of one router without Compose, set up the way
 * a freshly composed [NavigationRouter] sets it up: the root entry id is the
 * router id, and the router lives in the sub stacks of a hosting entry.
 *
 * Tests reach the router internals only through this driver, so a change of
 * those internals re-points the driver, not the tests.
 */
internal class NavigationRouterTestDriver(
    val routerId: String,
    initial: Route,
    initialStackId: String? = null,
) {
    private val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val host: NavigationEntry = NavigationEntryImpl(
        source = "test host",
        parent = parentScope,
        id = "host",
        route = HostRoute,
    )

    private val pile = navigationNodeStack(listOf(host)).let { hostPath ->
        val pileId = "$hostPath,$routerId"
        host.getOrCreate(pileId) {
            NavigationPile(
                id = pileId,
                routerId = routerId,
                parentScope = host.scope,
                initial = initialNavigationPileState(
                    routerId = routerId,
                    initial = initial,
                    initialStackId = initialStackId,
                ),
            )
        }
    }

    /**
     * Returns `null` if the router consumed the [intent], otherwise
     * the intent it passes to the parent controller.
     */
    fun dispatch(intent: NavigationIntent): NavigationIntent? = pile.dispatch(intent)

    /** The stack ids of the router, in order. The active stack is the last one. */
    val stackIds: List<String>
        get() = pile.value.map { it.id }

    val activeStackId: String
        get() = stackIds.last()

    fun entries(stackId: String = activeStackId): List<NavigationEntry> = pile.value
        .single { it.id == stackId }
        .value

    fun entryIds(stackId: String = activeStackId): List<String> =
        entries(stackId).map { it.id }

    fun routes(stackId: String = activeStackId): List<Route> =
        entries(stackId).map { it.route }

    /**
     * A live entry of any stack of the router. The roots that `NavigateToStack`
     * creates share the router id with the initial root, so once there are
     * several stacks get the roots from [entries] instead.
     */
    fun entry(id: String): NavigationEntry = pile.value
        .flatMap { it.value }
        .single { it.id == id }

    /** The id of the stack that `NavigateToStack(suffix, root)` addresses. */
    fun stackId(suffix: String): String = NavigationStack.createId(
        prefix = routerId,
        suffix = suffix,
    )

    /** The id of the initial stack of a router that starts at the [route]. */
    fun stackId(route: Route): String = stackId(NavigationStack.createIdSuffix(route))

    fun close() {
        parentScope.cancel()
    }

    private object HostRoute : Route {
        @Composable
        override fun Content() = Unit
    }
}
