package com.artemchep.keyguard.feature.home.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
import com.artemchep.keyguard.ui.focus.arrowKeyFocusNavigation
import com.artemchep.keyguard.ui.focus.awaitScrolledItemsPlaced
import com.artemchep.keyguard.ui.focus.saveFocusedChildOnExit
import com.artemchep.keyguard.ui.focus.scrollToItemInContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** Items kept in view above a restored row. */
private const val RESTORE_CONTEXT_ITEMS = 2

/**
 * Keyboard focus of a settings list. Arrow keys move between its rows.
 * In keyboard input mode the list focuses the target its navigation
 * entry focused last, see [rememberSettingsListFocus].
 */
@Stable
internal class SettingsListFocus(
    private val entry: NavigationEntry,
) {
    private class Target(
        val requester: FocusRequester,
        val modifier: Modifier,
        /** The key of the lazy item that holds the target. */
        val itemKey: String,
    )

    private val listRequester = FocusRequester()

    // Created on first use, so the list
    // does not have to know every target.
    private val targets = mutableMapOf<String, Target>()

    private var listFocused = false

    /** `true` while a detail pane next to the list shows an entry. */
    var detailOpen by mutableStateOf(false)

    // Each group on the way to a row remembers its focused child,
    // so the keyboard returns to the row from the home navigation.
    val listModifier: Modifier = Modifier
        .arrowKeyFocusNavigation()
        .focusRequester(listRequester)
        .onFocusChanged { listFocused = it.hasFocus }
        .saveFocusedChildOnExit()
        .focusGroup()
        // The lazy list's own focus group.
        .saveFocusedChildOnExit()

    /**
     * A target that is remembered when it gains focus. Use it on each
     * lazy item, and on the toolbar buttons that should be restored.
     * A row inside a lazy item passes the [itemKey] of that item, so the
     * list scrolls to it on restore.
     */
    fun target(key: String, itemKey: String = key): Modifier = targets.getOrPut(key) {
        val requester = FocusRequester()
        val modifier = Modifier
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    entry.lastFocusedKey = key
                }
            }
            .focusRequester(requester)
            .saveFocusedChildOnExit()
            .focusGroup()
        Target(requester, modifier, itemKey)
    }.modifier

    /**
     * Focuses the target its entry focused last, else the first row if
     * [focusFirst]. Does nothing while the list holds focus or
     * [canRestore] is `false`, also after a scroll to the target.
     */
    internal suspend fun restore(
        listState: LazyListState,
        listKeys: List<String>,
        focusFirst: Boolean,
        canRestore: () -> Boolean,
    ) {
        // A click or a Tab may move focus while the list scrolls.
        fun canFocus() = !listFocused && canRestore()
        if (!canFocus()) return

        val key = entry.lastFocusedKey
        if (key != null) scrollToTarget(key, listState, listKeys)
        if (!canFocus()) return

        // A target outside of the list, such as a toolbar
        // button, is composed whenever the list is. Enter picks
        // its top-left child. Down would pick a small footer
        // button over the wide row above it, as the button's
        // center is nearer to the start.
        val restored = key != null &&
            targets[key]?.requester?.requestFocus(FocusDirection.Enter) == true
        if (!restored && focusFirst) {
            // A group enters by position, Down picks its top row.
            listRequester.requestFocus(FocusDirection.Down)
        }
    }

    /** Scrolls the lazy item of the target [key] that is out of view into view. */
    private suspend fun scrollToTarget(
        key: String,
        listState: LazyListState,
        listKeys: List<String>,
    ) {
        val itemKey = targets[key]?.itemKey ?: key
        val index = listKeys.indexOf(itemKey)
        if (index < 0 || listState.layoutInfo.visibleItemsInfo.any { it.index == index }) return
        val contextIndex = (index - RESTORE_CONTEXT_ITEMS).coerceAtLeast(0)
        listState.scrollToItemInContext(index, contextIndex)
        // A group only enters a placed row.
        awaitScrolledItemsPlaced()
    }
}

/**
 * Focus for a settings list with the [listKeys] of its lazy items, in
 * order.
 *
 * Each time the content is live, has rows and no detail pane next to it
 * shows an entry, it restores focus in keyboard input mode, unless the
 * list or the home navigation holds focus: the target its navigation
 * entry focused last, else the first row if [focusFirstOnEnter].
 */
@Composable
internal fun rememberSettingsListFocus(
    listState: LazyListState,
    listKeys: List<String>,
    focusFirstOnEnter: Boolean,
): SettingsListFocus {
    val entry = LocalNavigationEntry.current
    val focus = remember(entry) {
        SettingsListFocus(entry)
    }
    DetailPaneEffect(focus)

    val finishing = LocalNavigationNodeFinishing.current
    val inputModeManager = LocalInputModeManager.current
    val homeNavigationFocused = LocalHomeNavigationFocused.current
    val currentListKeys by rememberUpdatedState(listKeys)
    // Not keyed on the input mode: the first Tab switches it,
    // and a restore then would take focus from where Tab moved it.
    LaunchedEffect(focus, finishing) {
        if (finishing) return@LaunchedEffect

        fun canRestore() = inputModeManager.inputMode == InputMode.Keyboard &&
            // A tab switched from the home navigation keeps focus there.
            !homeNavigationFocused()

        // A list next to a detail pane stays composed,
        // so it restores focus when the detail closes.
        snapshotFlow { currentListKeys.isNotEmpty() && !focus.detailOpen }
            .collectLatest { ready ->
                if (!ready || !canRestore()) return@collectLatest
                // Lazy items are composed by a layout that
                // comes after the content, maybe frames later.
                snapshotFlow { listState.hasMeasured(currentListKeys) }
                    .first { it }
                // The layout that measured them may still be placing
                // them, and a group only enters a placed row.
                withFrameNanos { }
                focus.restore(
                    listState = listState,
                    listKeys = currentListKeys,
                    focusFirst = focusFirstOnEnter,
                    canRestore = ::canRestore,
                )
            }
    }
    return focus
}

/**
 * Tells the [focus] if a detail pane next to its list shows an entry.
 * It reads the router in its own scope, so a push or a pop does not
 * recompose the list.
 */
@Composable
private fun DetailPaneEffect(focus: SettingsListFocus) {
    val detailOpen = LocalHasDetailPane.current &&
        navigationNextEntryOrNull() != null
    SideEffect {
        focus.detailOpen = detailOpen
    }
}

/** `true` once a layout measured the items of the [keys]. */
private fun LazyListState.hasMeasured(keys: List<String>): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull()
        ?: return false
    return keys.getOrNull(item.index) == item.key
}
