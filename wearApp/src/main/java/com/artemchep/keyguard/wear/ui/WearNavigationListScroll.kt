package com.artemchep.keyguard.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationScrollPosition
import com.artemchep.keyguard.feature.navigation.isNavigationNodeFinishing
import com.artemchep.keyguard.feature.navigation.resolve

private class WearNavigationListScrollHolder(
    val listState: TransformingLazyColumnState,
    pendingRestore: NavigationScrollPosition?,
) {
    // Snapshot state so the collector wakes up once a deferred restore finishes.
    var pendingRestore by mutableStateOf(pendingRestore)
}

/**
 * Remembers a [TransformingLazyColumnState] whose anchor scroll position is
 * saved on [LocalNavigationEntry] when leaving and restored when returning.
 *
 * [listKeys] must match the exact ordered keys emitted into the
 * `TransformingLazyColumn` (including [WEAR_SCAFFOLD_HEADER_KEY] when present),
 * or be empty while loading.
 */
@Composable
internal fun rememberWearNavigationListScrollState(
    listKeys: List<String>,
): TransformingLazyColumnState {
    val entry = LocalNavigationEntry.current
    val finishing = isNavigationNodeFinishing()
    val holder = remember(entry) {
        val saved = entry.scrollPosition
        val initialTarget = saved?.resolve(listKeys)
        val listState = TransformingLazyColumnState(
            initialAnchorItemIndex = initialTarget?.index ?: 0,
            initialAnchorItemScrollOffset = initialTarget?.offset ?: 0,
        )
        WearNavigationListScrollHolder(
            listState = listState,
            pendingRestore = if (initialTarget == null) saved else null,
        )
    }

    // Apply a deferred restore once loaded items arrive; keep pendingRestore set
    // until scrollToItem completes so the recorder cannot overwrite saved state.
    LaunchedEffect(holder, listKeys) {
        val pending = holder.pendingRestore
        if (pending != null && listKeys.isNotEmpty()) {
            val target = pending.resolve(listKeys)
            if (target != null) {
                holder.listState.scrollToItem(
                    index = target.index,
                    scrollOffset = target.offset,
                )
                holder.pendingRestore = null
            }
        }
    }

    if (!finishing) {
        RecordWearNavigationListScrollEffect(
            entry = entry,
            holder = holder,
            listKeys = listKeys,
        )
    }

    return holder.listState
}

@Composable
private fun RecordWearNavigationListScrollEffect(
    entry: NavigationEntry,
    holder: WearNavigationListScrollHolder,
    listKeys: List<String>,
) {
    val currentListKeys by rememberUpdatedState(listKeys)
    val listState = holder.listState

    LaunchedEffect(entry, holder) {
        snapshotFlow {
            if (holder.pendingRestore != null) {
                null
            } else {
                captureWearScrollPosition(
                    listState = listState,
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
                        captureWearScrollPosition(
                            listState = listState,
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

private fun captureWearScrollPosition(
    listState: TransformingLazyColumnState,
    listKeys: List<String>,
): NavigationScrollPosition? {
    val index = listState.anchorItemIndex
    val expectedKey = listKeys.getOrNull(index)
    val visibleItem = if (expectedKey != null) {
        listState.layoutInfo.visibleItems
            .firstOrNull { it.index == index }
    } else {
        null
    }
    return if (expectedKey != null && visibleItem?.key == expectedKey) {
        NavigationScrollPosition(
            itemKey = expectedKey,
            itemIndex = index,
            itemOffset = listState.anchorItemScrollOffset,
        )
    } else {
        null
    }
}
