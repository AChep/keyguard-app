package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The navigation of one window: the targets of its Back events and the
 * routers composed in it. Use it on the main thread only.
 */
class NavigationHost {
    private class BackTarget(
        val depth: () -> Int,
        val canPop: () -> Boolean,
        val onBack: () -> Unit,
    )

    private class AttachedRouter(
        val hostEntry: NavigationEntry,
    )

    // In registration order, so the latest target wins a tie.
    private val backTargets = mutableListOf<BackTarget>()

    private val backEnabledListeners = mutableListOf<(Boolean) -> Unit>()

    private val routers = mutableListOf<AttachedRouter>()

    /**
     * `true` if a Back target could pop, as of the last evaluation. Registering
     * or unregistering a target and [invalidateBack] evaluate it again.
     */
    var isBackEnabled: Boolean = false
        private set

    /**
     * Adds a target for Back. Among the targets that can pop, the deepest
     * one handles Back. Returns a function that removes the target.
     *
     * @param depth the depth of the target in the window, read when Back arrives
     * @param canPop a live check that the target can handle Back
     */
    fun registerBackTarget(
        depth: () -> Int = { 0 },
        canPop: () -> Boolean,
        onBack: () -> Unit,
    ): () -> Unit {
        val target = BackTarget(
            depth = depth,
            canPop = canPop,
            onBack = onBack,
        )
        backTargets += target
        invalidateBack()
        return {
            if (backTargets.remove(target)) {
                invalidateBack()
            }
        }
    }

    /** Handles unconsumed Escape events after the window's regular shortcuts. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isEscape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        val hasModifier =
            event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed
        if (!isEscape || hasModifier) {
            return false
        }
        return handleBack()
    }

    /**
     * Gives Back to the deepest target that can pop right now. Returns
     * `false` if no target can pop.
     */
    fun handleBack(): Boolean {
        // Read the live checks, not the last evaluation: another Back may
        // arrive before anything invalidates the host.
        var target: BackTarget? = null
        var targetDepth = 0
        backTargets.forEach { candidate ->
            if (!candidate.canPop()) {
                return@forEach
            }
            val depth = candidate.depth()
            if (target == null || depth >= targetDepth) {
                target = candidate
                targetDepth = depth
            }
        }
        val onBack = target?.onBack
            ?: return false
        onBack()
        return true
    }

    /**
     * Adds a [listener] of [isBackEnabled]. It is called when the value
     * changes, not with the current value. Returns a function that removes it.
     */
    fun addBackEnabledListener(listener: (Boolean) -> Unit): () -> Unit {
        backEnabledListeners += listener
        return {
            backEnabledListeners.remove(listener)
        }
    }

    /**
     * Evaluates [isBackEnabled] again and notifies the listeners
     * if the value changed.
     */
    fun invalidateBack() {
        val enabled = backTargets.any { it.canPop() }
        if (enabled == isBackEnabled) {
            return
        }
        isBackEnabled = enabled
        backEnabledListeners
            .toList()
            .forEach { listener ->
                listener(enabled)
            }
    }

    /**
     * Adds a router composed in the window. The router keeps its pile
     * in the sub stacks of the [hostEntry]. Returns a function that
     * removes the router.
     */
    internal fun attachRouter(hostEntry: NavigationEntry): () -> Unit {
        val router = AttachedRouter(hostEntry)
        routers += router
        return {
            routers.remove(router)
        }
    }

    /**
     * Saves the piles of the attached routers into the [store], together
     * with the piles of the routers nested in their entries.
     */
    fun saveRouters(store: NavigationStore) {
        val saved = HashSet<NavigationPile>()
        routers.forEach { router ->
            router.hostEntry.subStacks.values.forEach { pile ->
                savePile(store, pile, saved)
            }
        }
    }

    private fun savePile(
        store: NavigationStore,
        pile: NavigationPile,
        saved: MutableSet<NavigationPile>,
    ) {
        if (!saved.add(pile)) {
            return
        }
        store.save(pile.id, pile)
        pile.value.forEach { stack ->
            stack.value.forEach { entry ->
                entry.subStacks.values.forEach { childPile ->
                    savePile(store, childPile, saved)
                }
            }
        }
    }
}

val LocalNavigationHost = compositionLocalOf<NavigationHost> {
    val msg = "LocalNavigationHost is not provided."
    throw IllegalStateException(msg)
}

/**
 * Provides the [host] of the window to the [content].
 */
@Composable
fun NavigationHost(
    host: NavigationHost,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNavigationHost provides host,
        content = content,
    )
}
