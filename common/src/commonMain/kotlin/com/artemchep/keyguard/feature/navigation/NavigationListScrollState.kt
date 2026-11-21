package com.artemchep.keyguard.feature.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Scroll state for a lazy list screen and its top app bar, persisted on the
 * owning [NavigationEntry] while the entry stays alive in the back stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class NavigationListScrollState(
    val listState: LazyListState,
    val topAppBarState: TopAppBarState,
)

@OptIn(ExperimentalMaterial3Api::class)
private class NavigationListScrollHolder(
    val state: NavigationListScrollState,
    pendingRestore: NavigationScrollPosition?,
) {
    // Snapshot state so the collector wakes up once a deferred restore finishes.
    var pendingRestore by mutableStateOf(pendingRestore)
}

/**
 * Remembers a [LazyListState] and [TopAppBarState] whose scroll position is
 * saved on [LocalNavigationEntry] when leaving and restored when returning.
 *
 * Handles both synchronous lists (where [listKeys] is already non-empty on the
 * first frame) and asynchronous lists (where [listKeys] starts empty while
 * loading and populates later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberNavigationListScrollState(
    listKeys: List<String>,
): NavigationListScrollState {
    val entry = LocalNavigationEntry.current
    val finishing = LocalNavigationNodeFinishing.current
    val holder = remember(entry) {
        createScrollHolder(
            saved = entry.scrollPosition,
            listKeys = listKeys,
        )
    }

    // Apply a deferred restore before layout of the first frame with loaded items.
    SideEffect {
        val pending = holder.pendingRestore
        if (pending != null && listKeys.isNotEmpty()) {
            val target = pending.resolve(listKeys)
            if (target != null) {
                holder.pendingRestore = null
                holder.state.listState.requestScrollToItem(
                    index = target.index,
                    scrollOffset = target.offset.coerceAtLeast(0),
                )
            }
        }
    }

    ClampTopAppBarOffsetEffect(holder.state.topAppBarState)

    // Record live scroll updates; skip while finishing (exit animation or predictive back preview).
    if (!finishing) {
        RecordNavigationListScrollEffect(
            entry = entry,
            holder = holder,
            listKeys = listKeys,
        )
    }

    return holder.state
}

@OptIn(ExperimentalMaterial3Api::class)
private fun createScrollHolder(
    saved: NavigationScrollPosition?,
    listKeys: List<String>,
): NavigationListScrollHolder {
    val initialTarget = saved?.resolve(listKeys)
    val listState = LazyListState(
        firstVisibleItemIndex = initialTarget?.index ?: 0,
        firstVisibleItemScrollOffset = initialTarget?.offset?.coerceAtLeast(0) ?: 0,
    )
    val topAppBarState = if (saved != null) {
        TopAppBarState(
            initialHeightOffsetLimit = saved.toolbarHeightOffsetLimit,
            initialHeightOffset = saved.toolbarHeightOffset,
            initialContentOffset = saved.toolbarContentOffset,
        )
    } else {
        TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = 0f,
            initialContentOffset = 0f,
        )
    }
    return NavigationListScrollHolder(
        state = NavigationListScrollState(
            listState = listState,
            topAppBarState = topAppBarState,
        ),
        pendingRestore = if (initialTarget == null) saved else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClampTopAppBarOffsetEffect(
    topAppBarState: TopAppBarState,
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    // Clamp after the top app bar updates heightOffsetLimit on initial layout
    // or after a window/density change (e.g. orientation or font-scale change).
    LaunchedEffect(topAppBarState, containerSize, density) {
        withFrameNanos { }
        val limit = topAppBarState.heightOffsetLimit
        if (topAppBarState.heightOffset < limit) {
            topAppBarState.heightOffset = limit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordNavigationListScrollEffect(
    entry: NavigationEntry,
    holder: NavigationListScrollHolder,
    listKeys: List<String>,
) {
    val currentListKeys by rememberUpdatedState(listKeys)
    val listState = holder.state.listState
    val topAppBarState = holder.state.topAppBarState

    LaunchedEffect(entry, holder) {
        snapshotFlow {
            if (holder.pendingRestore != null) {
                null
            } else {
                captureScrollPosition(
                    listState = listState,
                    topAppBarState = topAppBarState,
                    listKeys = currentListKeys,
                )
            }
        }.collect { position ->
            if (position != null) {
                entry.scrollPosition = position
            }
        }
    }
    DisposableEffect(entry, holder) {
        onDispose {
            if (!entry.isDestroyed) {
                val captured = Snapshot.withoutReadObservation {
                    if (holder.pendingRestore != null) {
                        null
                    } else {
                        captureScrollPosition(
                            listState = listState,
                            topAppBarState = topAppBarState,
                            listKeys = currentListKeys,
                        )
                    }
                }
                if (captured != null) {
                    entry.scrollPosition = captured
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun captureScrollPosition(
    listState: LazyListState,
    topAppBarState: TopAppBarState,
    listKeys: List<String>,
): NavigationScrollPosition? {
    val index = listState.firstVisibleItemIndex
    val expectedKey = listKeys.getOrNull(index)
    val visibleItem = if (expectedKey != null) {
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
    } else {
        null
    }
    return if (expectedKey != null && visibleItem?.key == expectedKey) {
        NavigationScrollPosition(
            itemKey = expectedKey,
            itemIndex = index,
            itemOffset = listState.firstVisibleItemScrollOffset,
            toolbarHeightOffsetLimit = topAppBarState.heightOffsetLimit,
            toolbarHeightOffset = topAppBarState.heightOffset,
            toolbarContentOffset = topAppBarState.contentOffset,
        )
    } else {
        null
    }
}
