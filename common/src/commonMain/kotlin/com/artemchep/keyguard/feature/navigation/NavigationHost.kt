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

// Top level together with findBackTarget(), which keeps the host
// within the detekt function limit.
private class BackTarget(
    val depth: () -> Int,
    val canPop: () -> Boolean,
    val gesture: NavigationBackGestureListener?,
    val onBack: () -> Unit,
)

/**
 * The navigation of one window: the targets of its Back events and the
 * routers composed in it. Use it on the main thread only.
 */
class NavigationHost {
    /**
     * A Back gesture in flight. The [target] is `null` once the target
     * the gesture was picked for is removed.
     */
    private class BackGesture(
        var target: BackTarget?,
    )

    private class AttachedRouter(
        val hostEntry: NavigationEntry,
    )

    // In registration order, so the latest target wins a tie.
    private val backTargets = mutableListOf<BackTarget>()

    private var backGesture: BackGesture? = null

    private val backEnabledListeners = mutableListOf<(Boolean) -> Unit>()

    private val routers = mutableListOf<AttachedRouter>()

    /**
     * `true` if a Back gesture is in flight or a Back target could pop, as
     * of the last evaluation. Changes to the targets or the gesture and
     * [invalidateBack] evaluate it again.
     */
    var isBackEnabled: Boolean = false
        private set

    /**
     * Adds a target for Back. Among the targets that can pop, the deepest
     * one handles Back. Returns a function that removes the target.
     *
     * @param depth the depth of the target in the window, read when Back arrives
     * @param canPop a live check that the target can handle Back
     * @param gesture follows a Back gesture the target is picked for
     */
    fun registerBackTarget(
        depth: () -> Int = { 0 },
        canPop: () -> Boolean,
        gesture: NavigationBackGestureListener? = null,
        onBack: () -> Unit,
    ): () -> Unit {
        val target = BackTarget(
            depth = depth,
            canPop = canPop,
            gesture = gesture,
            onBack = onBack,
        )
        backTargets += target
        invalidateBack()
        return {
            if (backTargets.remove(target)) {
                val inFlight = backGesture
                if (inFlight != null && inFlight.target === target) {
                    // The gesture stays in flight, so the host
                    // consumes its release.
                    inFlight.target = null
                    target.gesture?.onBackCancelled()
                }
                invalidateBack()
            }
        }
    }

    /**
     * Starts a Back gesture and cancels the one in flight. It picks the
     * target the same way [handleBack] does and keeps it until
     * [handleBack] or [cancelBack]. If no target can pop, it records nothing.
     */
    fun startBack(event: NavigationBackEvent) {
        if (backGesture != null) {
            cancelBack()
        }
        val target = backTargets.findBackTarget()
            ?: return
        backGesture = BackGesture(target)
        target.gesture?.onBackStarted(event)
        invalidateBack()
    }

    /** Passes the progress of the Back gesture to its target. */
    fun progressBack(event: NavigationBackEvent) {
        backGesture?.target?.gesture?.onBackProgressed(event)
    }

    /** Cancels the Back gesture in flight. */
    fun cancelBack() {
        val target = backGesture?.target
        backGesture = null
        target?.gesture?.onBackCancelled()
        invalidateBack()
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
     *
     * The release of a Back gesture goes to the target picked at its
     * start. If that target is removed or can no longer pop, the release
     * is consumed and nothing pops.
     */
    fun handleBack(): Boolean {
        val gesture = backGesture
        if (gesture != null) {
            // Take the gesture first, so a target removed by
            // the calls below is not cancelled.
            backGesture = null
            val target = gesture.target
            when {
                // Cancelled when it was removed.
                target == null -> Unit
                target.canPop() -> target.onBack()
                else -> target.gesture?.onBackCancelled()
            }
            invalidateBack()
            return true
        }
        // Read the live checks, not the last evaluation: another Back may
        // arrive before anything invalidates the host.
        val target = backTargets.findBackTarget()
        target?.onBack?.invoke()
        return target != null
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
        // The host consumes the release of a gesture in flight, even if
        // no target can pop. Otherwise the platform takes it instead.
        val enabled = backGesture != null || backTargets.any { it.canPop() }
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

/**
 * The deepest target that can pop right now. A tie in depth goes to
 * the latest registration.
 */
private fun List<BackTarget>.findBackTarget(): BackTarget? {
    var target: BackTarget? = null
    var targetDepth = 0
    forEach { candidate ->
        if (!candidate.canPop()) {
            return@forEach
        }
        val depth = candidate.depth()
        if (target == null || depth >= targetDepth) {
            target = candidate
            targetDepth = depth
        }
    }
    return target
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
