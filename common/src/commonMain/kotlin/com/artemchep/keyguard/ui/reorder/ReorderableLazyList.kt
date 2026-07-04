package com.artemchep.keyguard.ui.reorder

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun rememberReorderableLazyListState(
    listState: LazyListState,
    itemKeys: Collection<Any>,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragStarted: () -> Unit = {},
    onDragStopped: (shouldCommit: Boolean) -> Unit = {},
    autoScrollThreshold: Dp = 72.dp,
    autoScrollMaxStep: Dp = 20.dp,
): ReorderableLazyListState {
    val coroutineScope = rememberCoroutineScope()
    val state = remember(
        listState,
        coroutineScope,
    ) {
        ReorderableLazyListState(
            listState = listState,
            coroutineScope = coroutineScope,
        )
    }

    val density = LocalDensity.current
    state.itemKeys = itemKeys.toSet()
    state.onMove = onMove
    state.onDragStarted = onDragStarted
    state.onDragStopped = onDragStopped
    state.autoScrollThresholdPx = with(density) {
        autoScrollThreshold.toPx()
    }
    state.autoScrollMaxStepPx = with(density) {
        autoScrollMaxStep.toPx()
    }
    return state
}

class ReorderableLazyListState internal constructor(
    private val listState: LazyListState,
    private val coroutineScope: CoroutineScope,
) {
    internal var itemKeys: Set<Any> = emptySet()
    internal var onMove: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> }
    internal var onDragStarted: () -> Unit = {}
    internal var onDragStopped: (shouldCommit: Boolean) -> Unit = {}
    internal var autoScrollThresholdPx: Float = 0f
    internal var autoScrollMaxStepPx: Float = 0f

    var draggingItemKey by mutableStateOf<Any?>(null)
        private set

    var dragOffset by mutableFloatStateOf(0f)
        private set

    private var draggingIndex: Int? = null
    private var draggedItemCenter = 0f
    private var autoScrollJob: Job? = null

    fun isDragging(
        itemKey: Any,
    ): Boolean = draggingItemKey == itemKey

    fun dragOffsetFor(
        itemKey: Any,
    ): Float = if (isDragging(itemKey)) {
        dragOffset
    } else {
        0f
    }

    internal fun onDragStart(
        itemKey: Any,
    ): Boolean {
        if (itemKey !in itemKeys) {
            return false
        }

        val itemInfo = visibleItemInfo(itemKey)
            ?: return false
        draggingItemKey = itemKey
        draggedItemCenter = itemInfo.offset + itemInfo.size / 2f
        updateDraggedItemInfo(itemInfo)
        onDragStarted()
        return true
    }

    internal fun onDrag(
        dragAmountY: Float,
    ) {
        val itemKey = draggingItemKey
            ?: return
        draggedItemCenter += dragAmountY
        visibleItemInfo(itemKey)?.let(::updateDraggedItemInfo)
        moveIfNeeded()
        autoScrollIfNeeded()
    }

    internal fun onDragEnd() {
        finishDrag(
            shouldCommit = true,
        )
    }

    internal fun onDragCancel() {
        finishDrag(
            shouldCommit = false,
        )
    }

    private fun moveIfNeeded() {
        val index = draggingIndex
            ?: return
        val targetItem = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { itemInfo ->
                itemInfo.key in itemKeys &&
                        itemInfo.index != index &&
                        draggedItemCenter >= itemInfo.offset &&
                        draggedItemCenter <= itemInfo.offset + itemInfo.size
            }
        if (targetItem != null) {
            onMove(index, targetItem.index)
            updateDraggedItemInfo(targetItem)
        }
    }

    private fun updateDraggedItemInfo(
        itemInfo: LazyListItemInfo,
    ) {
        draggingIndex = itemInfo.index
        dragOffset = draggedItemCenter - (itemInfo.offset + itemInfo.size / 2f)
    }

    private fun finishDrag(
        shouldCommit: Boolean,
    ) {
        if (draggingItemKey == null) {
            return
        }

        autoScrollJob?.cancel()
        autoScrollJob = null
        draggingItemKey = null
        draggingIndex = null
        draggedItemCenter = 0f
        dragOffset = 0f
        onDragStopped(shouldCommit)
    }

    private fun autoScrollIfNeeded() {
        if (calculateAutoScrollDelta() == 0f) {
            autoScrollJob?.cancel()
            autoScrollJob = null
            return
        }
        if (autoScrollJob?.isActive == true) {
            return
        }
        autoScrollJob = coroutineScope.launch {
            while (isActive) {
                val autoScrollDelta = calculateAutoScrollDelta()
                if (autoScrollDelta == 0f) {
                    break
                }

                val consumedScroll = listState.scrollBy(autoScrollDelta)
                if (consumedScroll == 0f) {
                    break
                }

                draggingItemKey
                    ?.let(::visibleItemInfo)
                    ?.let(::updateDraggedItemInfo)
                moveIfNeeded()
                delay(16L)
            }
            autoScrollJob = null
        }
    }

    private fun calculateAutoScrollDelta(): Float {
        val threshold = autoScrollThresholdPx
        val maxStep = autoScrollMaxStepPx
        if (threshold <= 0f || maxStep <= 0f || draggingItemKey == null) {
            return 0f
        }

        val layoutInfo = listState.layoutInfo
        val topDistance = draggedItemCenter - layoutInfo.viewportStartOffset
        val bottomDistance = layoutInfo.viewportEndOffset - draggedItemCenter
        return when {
            topDistance < threshold -> -calculateAutoScrollStep(
                distanceToEdge = topDistance,
                threshold = threshold,
                maxStep = maxStep,
            )

            bottomDistance < threshold -> calculateAutoScrollStep(
                distanceToEdge = bottomDistance,
                threshold = threshold,
                maxStep = maxStep,
            )

            else -> 0f
        }
    }

    private fun calculateAutoScrollStep(
        distanceToEdge: Float,
        threshold: Float,
        maxStep: Float,
    ): Float {
        val progress = ((threshold - distanceToEdge) / threshold)
            .coerceIn(0f, 1f)
        return (maxStep * progress)
            .coerceAtLeast(1f)
    }

    private fun visibleItemInfo(
        itemKey: Any,
    ) = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { itemInfo ->
            itemInfo.key == itemKey
        }
}

@Composable
fun Modifier.reorderableLazyListItem(
    reorderState: ReorderableLazyListState,
    itemKey: Any,
    shape: Shape = RectangleShape,
    shapePadding: PaddingValues = PaddingValues(0.dp),
    draggingZIndex: Float = 1f,
    draggingShadowElevation: Float = 4f,
): Modifier {
    val isDragging = reorderState.isDragging(itemKey)
    val dragOffset = reorderState.dragOffsetFor(itemKey)
    return this
        .zIndex(
            if (isDragging) {
                draggingZIndex
            } else {
                0f
            },
        )
        .graphicsLayer {
            translationY = dragOffset
            shadowElevation = if (isDragging) {
                draggingShadowElevation
            } else {
                0f
            }
            this.shape = PaddedShape(
                shape = shape,
                padding = shapePadding,
            )
        }
}

private data class PaddedShape(
    private val shape: Shape,
    private val padding: PaddingValues,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = with(density) {
        val left = padding
            .calculateLeftPadding(layoutDirection)
            .toPx()
            .coerceAtLeast(0f)
        val top = padding
            .calculateTopPadding()
            .toPx()
            .coerceAtLeast(0f)
        val right = padding
            .calculateRightPadding(layoutDirection)
            .toPx()
            .coerceAtLeast(0f)
        val bottom = padding
            .calculateBottomPadding()
            .toPx()
            .coerceAtLeast(0f)
        val insetSize = Size(
            width = (size.width - left - right)
                .coerceAtLeast(0f),
            height = (size.height - top - bottom)
                .coerceAtLeast(0f),
        )
        shape
            .createOutline(
                size = insetSize,
                layoutDirection = layoutDirection,
                density = this,
            )
            .translate(
                offset = Offset(left, top),
            )
    }
}

private fun Outline.translate(
    offset: Offset,
): Outline = when (this) {
    is Outline.Rectangle -> Outline.Rectangle(
        rect = rect.translate(offset),
    )

    is Outline.Rounded -> {
        Outline.Rounded(
            roundRect = RoundRect(
                left = roundRect.left + offset.x,
                top = roundRect.top + offset.y,
                right = roundRect.right + offset.x,
                bottom = roundRect.bottom + offset.y,
                topLeftCornerRadius = roundRect.topLeftCornerRadius,
                topRightCornerRadius = roundRect.topRightCornerRadius,
                bottomRightCornerRadius = roundRect.bottomRightCornerRadius,
                bottomLeftCornerRadius = roundRect.bottomLeftCornerRadius,
            ),
        )
    }

    is Outline.Generic -> {
        val translatedPath = Path()
        translatedPath.addPath(path, offset)
        Outline.Generic(translatedPath)
    }
}

@Composable
fun Modifier.reorderableLazyListDragHandle(
    reorderState: ReorderableLazyListState,
    itemKey: Any,
    enabled: Boolean = true,
    startDragImmediately: Boolean = false,
    onDragStarted: () -> Unit = {},
): Modifier {
    val updatedOnDragStarted by rememberUpdatedState(onDragStarted)
    if (!enabled) {
        return this
    }

    return pointerInput(
        reorderState,
        itemKey,
        startDragImmediately,
    ) {
        if (startDragImmediately) {
            detectDragGestures(
                onDragStart = {
                    if (reorderState.onDragStart(itemKey)) {
                        updatedOnDragStarted()
                    }
                },
                onDragCancel = reorderState::onDragCancel,
                onDragEnd = reorderState::onDragEnd,
            ) { change, dragAmount ->
                change.consume()
                reorderState.onDrag(
                    dragAmountY = dragAmount.y,
                )
            }
        } else {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    if (reorderState.onDragStart(itemKey)) {
                        updatedOnDragStarted()
                    }
                },
                onDragCancel = reorderState::onDragCancel,
                onDragEnd = reorderState::onDragEnd,
            ) { change, dragAmount ->
                change.consume()
                reorderState.onDrag(
                    dragAmountY = dragAmount.y,
                )
            }
        }
    }
}

fun <T> List<T>.move(
    fromIndex: Int,
    toIndex: Int,
): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) {
        return this
    }
    return toMutableList()
        .apply {
            add(toIndex, removeAt(fromIndex))
        }
}
