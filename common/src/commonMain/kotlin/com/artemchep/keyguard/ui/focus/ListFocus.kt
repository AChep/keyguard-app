package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import com.artemchep.keyguard.feature.home.LocalHomeNavigationFocused
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeFinishing
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Items kept in view above a restored row. */
private const val RESTORE_CONTEXT_ITEMS = 2

/**
 * Keyboard focus of a lazy list. Arrow keys move between its rows.
 * In keyboard input mode the list focuses the target its navigation
 * entry focused last, see [rememberListFocus].
 *
 * Left or Right into the list from outside, such as from a pane next to
 * it, focuses the row focused last if it is composed. Otherwise focus
 * goes where the search finds. Other entries, such as a restore from the
 * home navigation, enter as they asked.
 */
@Stable
internal class ListFocus(
    private val entry: NavigationEntry,
    private val scope: CoroutineScope,
) {
    private class Target(
        val requester: FocusRequester,
        val modifier: Modifier,
        /** The key of the lazy item that holds the target. */
        val itemKey: String,
    )

    /** Attached to the focus group of the list. */
    internal val listRequester = FocusRequester()

    // Only the composed targets, so a long list
    // holds no state for the rows out of view.
    private val targets = mutableMapOf<String, Target>()

    /** The item key of the row focused last, its row may be gone. */
    private var lastItemKey: String? = null

    private var listFocused = false

    // Only the composed regions, see rememberRegion.
    private val regions = mutableSetOf<ListFocusRegion>()

    /** `true` while one of its regions holds focus. Reading it does not recompose. */
    internal val anyRegionHasFocus: Boolean get() = regions.any { it.hasFocus }

    /** `true` while the list or one of its regions holds focus. Reading it does not recompose. */
    internal val anyHasFocus: Boolean get() = listFocused || anyRegionHasFocus

    // Set by rememberListFocus. A restore reads them when it runs.
    internal var listState: LazyListState? = null

    // By reference, so a write does not compare the keys.
    internal var listKeys: List<String> by mutableStateOf(emptyList(), referentialEqualityPolicy())

    private var handOffJob: Job? = null

    /** Where an Up from the top of the list goes, see [rememberListFocus]. */
    internal var exitUp: FocusRequester? = null

    // Attached to the top bar while it is linked, see rememberScaffoldContentFocus.
    private val topBarRequester = FocusRequester()

    private var topBarLinked = false

    /** `true` while a detail pane next to the list shows an entry. */
    var detailOpen by mutableStateOf(false)

    // A request redirects this focus change, see enterAt. Only a side
    // entry: a restore from the home navigation and a hand off enter
    // with Enter, and get what they asked for.
    private val onListEnter: FocusEnterExitScope.() -> Unit = {
        if (
            requestedFocusDirection == FocusDirection.Left ||
            requestedFocusDirection == FocusDirection.Right
        ) {
            // A row out of view is not composed, so the search result stands.
            val target = entry.viewMemory.lastFocusedRowKey?.let { key -> targets[key] }
            target?.requester?.requestFocus(FocusDirection.Enter)
        }
    }

    // Focus has not moved yet, so the child still holds it.
    // An exit has one handler per group, so it saves too.
    private val onListExit: FocusEnterExitScope.() -> Unit = {
        listRequester.saveFocusedChild()
        if (requestedFocusDirection == FocusDirection.Up) {
            // If it fails, focus goes where the search found.
            val up = exitUp ?: topBarRequester.takeIf { topBarLinked }
            up?.requestFocus(FocusDirection.Enter)
        }
    }

    // Each group on the way to a row remembers its focused child,
    // so the keyboard returns to the row from the home navigation.
    val listModifier: Modifier = Modifier
        .arrowKeyFocusNavigation()
        .focusRequester(listRequester)
        .onFocusChanged { listFocused = it.hasFocus }
        .focusProperties {
            onEnter = onListEnter
            onExit = onListExit
        }
        .focusGroup()
        // The lazy list's own focus group.
        .saveFocusedChildOnExit()

    /**
     * A region next to the list, such as its search field, a pane or
     * its buttons. The list does not restore focus while the region
     * holds it. The region is registered while it is composed.
     */
    @Composable
    fun rememberRegion(): ListFocusRegion {
        val region = remember(this) {
            ListFocusRegion(this)
        }
        DisposableEffect(region) {
            regions += region
            onDispose {
                regions -= region
            }
        }
        return region
    }

    /**
     * Links the floating action buttons on top of the list to it. The 2D
     * search from a button skips the list, as the list lies under the
     * buttons. So the buttons are a region of the list: Up from a button
     * focuses the list, see [ListFocusRegion.focusList], Left and Right
     * move between the buttons of [rememberFabFocusOrder] and stop at its
     * ends, and Down does nothing.
     *
     * With [topBar], the top bar is a region of the list too, so the list
     * does not restore focus while the bar holds it, such as after a Tab
     * to Back while the rows load. Down from the bar focuses the list,
     * Left and Right move along the bar and stop at its ends, and Up does
     * nothing. Up from the top row enters the bar, unless the list has an
     * `exitUp` of its own.
     */
    @Composable
    fun rememberScaffoldContentFocus(topBar: Boolean = false): ScaffoldContentFocus {
        val fabs = rememberRegion()
        val topBarModifier = if (topBar) rememberTopBarModifier() else null
        return remember(fabs, topBarModifier) {
            ScaffoldContentFocus(
                topBar = topBarModifier,
                fabs = fabs.barModifier(),
            )
        }
    }

    @Composable
    private fun rememberTopBarModifier(): Modifier {
        val bar = rememberRegion()
        DisposableEffect(bar) {
            topBarLinked = true
            onDispose {
                topBarLinked = false
            }
        }
        return remember(bar) {
            bar.barModifier(
                requester = topBarRequester,
                contentDirection = FocusDirection.Down,
            )
        }
    }

    /**
     * Moves focus from outside of the list, such as from one of its
     * regions, to the row focused last, else to the first visible row.
     * Never to a target outside of the list: Down from a remembered
     * toolbar button would focus the button again. A remembered row out
     * of view is scrolled into view first. Does nothing once [canRestore]
     * is `false`, such as when focus left the region that called it.
     * Call it from a key handler.
     */
    internal fun handOff(canRestore: () -> Boolean) {
        if (listState == null) return
        handOffJob?.cancel()
        // A visible target takes focus before the key handler returns.
        handOffJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            restore(
                key = entry.viewMemory.lastFocusedRowKey,
                focusFirst = true,
                canRestore = canRestore,
            )
        }
    }

    /**
     * A target that is remembered when it gains focus. Use it on each
     * lazy item, and on the toolbar buttons that should be restored.
     * A row inside a lazy item passes the [itemKey] of that item, so the
     * list scrolls to it on restore.
     *
     * A target outside of the list, such as a toolbar button, passes
     * `false` as [inList]. Coming back to the list restores it, but a
     * hand off from a region and a side entry go to the row focused last.
     * Such a target must be composed whenever the list is.
     */
    @Composable
    fun target(key: String, itemKey: String = key, inList: Boolean = true): Modifier {
        val target = remember(this, key, itemKey, inList) {
            val requester = FocusRequester()
            val modifier = Modifier
                .onFocusChanged { state ->
                    if (state.hasFocus) {
                        entry.viewMemory.lastFocusedKey = key
                        if (inList) {
                            entry.viewMemory.lastFocusedRowKey = key
                            lastItemKey = itemKey
                        }
                    }
                }
                .focusRequester(requester)
                .saveFocusedChildOnExit()
                .focusGroup()
            Target(requester, modifier, itemKey)
        }
        DisposableEffect(target) {
            targets[key] = target
            onDispose {
                // Another composition of the key may have replaced it.
                if (targets[key] === target) targets.remove(key)
            }
        }
        return target.modifier
    }

    /** The number of composed targets. */
    internal val targetCount: Int get() = targets.size

    /**
     * Focuses the target of the [key], else the first row if [focusFirst].
     * Does nothing while the list holds focus or [canRestore] is `false`,
     * also after a scroll to the target.
     */
    internal suspend fun restore(
        key: String?,
        focusFirst: Boolean,
        canRestore: () -> Boolean,
    ) {
        // A click or a Tab may move focus while the list scrolls.
        fun canFocus() = !listFocused && canRestore()
        if (!canFocus()) return

        if (key != null) scrollToTarget(key)
        if (!canFocus()) return

        // Enter picks its top-left child. Down would pick a small
        // footer button over the wide row above it, as the button's
        // center is nearer to the start.
        val restored = key != null &&
            targets[key]?.requester?.requestFocus(FocusDirection.Enter) == true
        if (!restored && focusFirst) {
            // A group enters its top-left placed row, for the same reason.
            listRequester.requestFocus(FocusDirection.Enter)
        }
    }

    /** Scrolls the lazy item of the target [key] that is out of view into view. */
    private suspend fun scrollToTarget(key: String) {
        val listState = listState ?: return
        // A target out of view is not composed, so it is not in the map.
        val itemKey = targets[key]?.itemKey ?: lastItemKey ?: key
        val index = listKeys.indexOf(itemKey)
        if (index < 0 || listState.layoutInfo.visibleItemsInfo.any { it.index == index }) return
        val contextIndex = (index - RESTORE_CONTEXT_ITEMS).coerceAtLeast(0)
        listState.scrollToItemInContext(index, contextIndex)
        // A group only enters a placed row.
        awaitScrolledItemsPlaced()
    }
}

/**
 * A region next to the list of a [ListFocus], see [ListFocus.rememberRegion].
 * Use the [modifier] on the region.
 */
@Stable
internal class ListFocusRegion internal constructor(
    private val list: ListFocus,
) {
    /** `true` while the region holds focus. Reading it does not recompose. */
    var hasFocus = false
        private set

    val modifier: Modifier = Modifier
        .onFocusChanged { hasFocus = it.hasFocus }

    /**
     * Moves focus from the region to the list, see [ListFocus.handOff].
     * Does nothing once focus left the region. Call it from a key handler.
     */
    fun focusList() {
        list.handOff { hasFocus }
    }

    /**
     * The modifier of a bar of buttons that is this region: Left and
     * Right move along it and stop at its ends, the [contentDirection]
     * key focuses the list, see [navigationBarFocusGroup].
     */
    fun barModifier(
        requester: FocusRequester? = null,
        contentDirection: FocusDirection? = null,
    ): Modifier = modifier
        // The region comes first, so it sees the focus of the bar.
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .navigationBarFocusGroup(
            orientation = Orientation.Horizontal,
            focusedState = null,
            onFocusContent = ::focusList,
            contentDirection = contentDirection,
        )
}

/**
 * Focus for a lazy list with the [listKeys] of its lazy items, in order.
 * The keys may be a cheap view of the items, such as an `AbstractList`
 * that maps an index to a key in O(1): only a restore searches them.
 *
 * Each time the content is live, has rows and no detail pane next to it
 * shows an entry, it restores focus in keyboard input mode, unless the
 * list, one of its [ListFocus.rememberRegion] regions or the home
 * navigation holds focus: the target its navigation entry focused last,
 * else the first row if [focusFirstOnEnter].
 *
 * Up from the top of the list focuses [exitUp], if it is set and takes
 * focus. Otherwise focus goes where the search finds.
 */
@Composable
internal fun rememberListFocus(
    listState: LazyListState,
    listKeys: List<String>,
    focusFirstOnEnter: Boolean,
    exitUp: FocusRequester? = null,
): ListFocus {
    val entry = LocalNavigationEntry.current
    val scope = rememberCoroutineScope()
    val focus = remember(entry) {
        ListFocus(entry, scope)
    }
    SideEffect {
        focus.exitUp = exitUp
        focus.listState = listState
        focus.listKeys = listKeys
    }
    DetailPaneEffect(focus)

    val canTakeFocus = rememberCanTakeFocus()
    // Not keyed on the input mode: the first Tab switches it,
    // and a restore then would take focus from where Tab moved it.
    LaunchedEffect(focus, canTakeFocus) {
        if (canTakeFocus == null) return@LaunchedEffect

        fun canRestore() = canTakeFocus() &&
            // A region next to the list keeps focus: a query that empties
            // and refills the list, a filter toggled in a pane or a button.
            !focus.anyRegionHasFocus

        // A list next to a detail pane stays composed,
        // so it restores focus when the detail closes.
        snapshotFlow { focus.listKeys.isNotEmpty() && !focus.detailOpen }
            .collectLatest { ready ->
                if (!ready || !canRestore()) return@collectLatest
                listState.awaitItemsPlaced { focus.listKeys }
                focus.restore(
                    // Also a target outside of the list, such as a toolbar button.
                    key = entry.viewMemory.lastFocusedKey,
                    focusFirst = focusFirstOnEnter,
                    canRestore = ::canRestore,
                )
            }
    }
    return focus
}

/**
 * Checks if a screen may move focus on its own: in keyboard input mode,
 * unless the home navigation holds focus, as a tab switched from there
 * keeps focus there. It is `null` while the navigation node is finishing,
 * so an effect keyed on it starts over once the node stops finishing.
 */
@Composable
internal fun rememberCanTakeFocus(): (() -> Boolean)? {
    val finishing = LocalNavigationNodeFinishing.current
    val inputModeManager = LocalInputModeManager.current
    val homeNavigationFocused = LocalHomeNavigationFocused.current
    return remember(finishing, inputModeManager, homeNavigationFocused) {
        val canTakeFocus = {
            inputModeManager.inputMode == InputMode.Keyboard && !homeNavigationFocused()
        }
        canTakeFocus.takeUnless { finishing }
    }
}

/**
 * Tells the [focus] if a detail pane next to its list shows an entry.
 * It reads the router in its own scope, so a push or a pop does not
 * recompose the list.
 */
@Composable
private fun DetailPaneEffect(focus: ListFocus) {
    val detailOpen = LocalHasDetailPane.current &&
        navigationNextEntryOrNull() != null
    SideEffect {
        focus.detailOpen = detailOpen
    }
}
