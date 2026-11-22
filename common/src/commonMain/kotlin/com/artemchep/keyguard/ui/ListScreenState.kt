package com.artemchep.keyguard.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.navigation.rememberNavigationListScrollState
import com.artemchep.keyguard.ui.focus.ListFocus
import com.artemchep.keyguard.ui.focus.rememberListFocus
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior

/**
 * The scroll and the keyboard focus of a list screen with a top app bar,
 * see [rememberListScreenState]. Pass the parts to a [ScaffoldLazyColumn].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal data class ListScreenState(
    val listState: LazyListState,
    val scrollBehavior: TopAppBarScrollBehavior,
    val focus: ListFocus,
)

/**
 * The state of a list screen with the [listKeys] of its lazy items, in
 * order. Its navigation entry keeps the scroll position and the toolbar
 * state while it is alive, a new entry starts at the top. The list
 * restores keyboard focus as [rememberListFocus] does, and focuses its
 * first row with nothing focused yet if [focusFirstOnEnter].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberListScreenState(
    listKeys: List<String>,
    focusFirstOnEnter: Boolean,
): ListScreenState {
    val scrollState = rememberNavigationListScrollState(
        listKeys = listKeys,
    )
    val scrollBehavior = ToolbarBehavior.behavior(
        state = scrollState.topAppBarState,
    )
    val focus = rememberListFocus(
        listState = scrollState.listState,
        listKeys = listKeys,
        focusFirstOnEnter = focusFirstOnEnter,
    )
    return remember(scrollState, scrollBehavior, focus) {
        ListScreenState(
            listState = scrollState.listState,
            scrollBehavior = scrollBehavior,
            focus = focus,
        )
    }
}
