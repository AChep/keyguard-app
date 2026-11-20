package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/**
 * Scrolls to [contextIndex] to keep a few items before [index] in view,
 * or to the item itself if it is not composed then.
 */
internal suspend fun LazyListState.scrollToItemInContext(index: Int, contextIndex: Int) {
    scrollToItem(contextIndex)
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
    }
}

/**
 * Waits until the items a scroll brought in are placed. A scroll only
 * measures them, and a frame runs its callbacks before its layout, so
 * the second frame comes after the layout that places them.
 */
internal suspend fun awaitScrolledItemsPlaced() {
    repeat(2) {
        withFrameNanos { }
    }
}

/**
 * Scrolls the least amount that shows the whole item at [index] between
 * the content paddings. Does nothing if the item is already in view, and
 * jumps to it if the list did not measure it. An item taller than the
 * view is aligned to its top. Expects a list that is not reversed.
 */
internal suspend fun LazyListState.scrollIntoView(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index)
        return
    }
    // Item offsets start after the content padding before the items.
    val viewStart = info.viewportStartOffset + info.beforeContentPadding
    val viewEnd = info.viewportEndOffset - info.afterContentPadding
    val itemStart = item.offset
    val itemEnd = item.offset + item.size
    val delta = when {
        itemStart < viewStart -> itemStart - viewStart
        itemEnd > viewEnd -> minOf(itemEnd - viewEnd, itemStart - viewStart)
        else -> return
    }
    scrollBy(delta.toFloat())
}
