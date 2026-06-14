package com.artemchep.keyguard.feature.attachmentpreview.minimap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewLineIndex
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AttachmentPreviewCodeMinimap(
    lineIndex: AttachmentPreviewLineIndex,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
    ) {
        val density = LocalDensity.current
        val minRowHeightPx = with(density) {
            AttachmentPreviewMinimapMinRowHeight.toPx()
        }
        val maxRows = remember(
            constraints.maxHeight,
            minRowHeightPx,
        ) {
            (constraints.maxHeight.toFloat() / minRowHeightPx)
                .toInt()
                .coerceAtLeast(1)
        }
        val bucketedMaxRows = remember(maxRows) {
            attachmentPreviewMinimapBucketedMaxRows(maxRows)
        }
        val rowCache = remember(lineIndex) {
            mutableStateMapOf<Int, List<AttachmentPreviewMinimapRow>>()
        }
        var lastRows by remember(lineIndex) {
            mutableStateOf<List<AttachmentPreviewMinimapRow>>(emptyList())
        }
        val rows = rowCache[bucketedMaxRows] ?: lastRows
        LaunchedEffect(
            lineIndex,
            bucketedMaxRows,
        ) {
            val cachedRows = rowCache[bucketedMaxRows]
            if (cachedRows != null) {
                lastRows = cachedRows
                return@LaunchedEffect
            }

            val builtRows = withContext(Dispatchers.Default) {
                buildAttachmentPreviewMinimapRows(
                    lineIndex = lineIndex,
                    maxRows = bucketedMaxRows,
                )
            }
            rowCache[bucketedMaxRows] = builtRows
            lastRows = builtRows
        }
        val viewport by remember(
            listState,
            lineIndex.size,
        ) {
            derivedStateOf {
                listState.attachmentPreviewMinimapViewport(
                    lineCount = lineIndex.size,
                )
            }
        }

        val lineColor = LocalContentColor.current
            .combineAlpha(DisabledEmphasisAlpha)
        val viewportColor = MaterialTheme.colorScheme.primary
            .copy(alpha = 0.24f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    constraints.maxHeight,
                    lineIndex.size,
                    listState,
                ) {
                    coroutineScope {
                        val scrollTargets = Channel<Int>(Channel.CONFLATED)
                        val scrollWorker = launch {
                            for (targetLineIndex in scrollTargets) {
                                listState.scrollToItem(
                                    index = targetLineIndex,
                                )
                            }
                        }

                        fun sendTargetLineIndex(
                            offsetY: Float,
                            previousTargetLineIndex: Int?,
                        ): Int {
                            val pointerLineIndex = attachmentPreviewMinimapLineIndexAtOffset(
                                lineCount = lineIndex.size,
                                offsetY = offsetY,
                                height = constraints.maxHeight.toFloat(),
                            )
                            val targetLineIndex = attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
                                lineCount = lineIndex.size,
                                pointerLineIndex = pointerLineIndex,
                                visibleLineCount = listState.layoutInfo.visibleItemsInfo.size,
                            )
                            if (targetLineIndex != previousTargetLineIndex) {
                                scrollTargets.trySend(targetLineIndex)
                            }
                            return targetLineIndex
                        }

                        try {
                            awaitEachGesture {
                                var lastTargetLineIndex: Int? = null
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                lastTargetLineIndex = sendTargetLineIndex(
                                    offsetY = down.position.y,
                                    previousTargetLineIndex = lastTargetLineIndex,
                                )

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (change == null || !change.pressed) {
                                        break
                                    }

                                    change.consume()
                                    lastTargetLineIndex = sendTargetLineIndex(
                                        offsetY = change.position.y,
                                        previousTargetLineIndex = lastTargetLineIndex,
                                    )
                                }
                            }
                        } finally {
                            scrollTargets.close()
                            scrollWorker.cancel()
                        }
                    }
                },
        ) {
            if (rows.isEmpty()) {
                return@Canvas
            }

            val horizontalPadding = AttachmentPreviewMinimapHorizontalPadding.toPx()
            val availableWidth = (size.width - horizontalPadding * 2f)
                .coerceAtLeast(0f)
            val rowHeight = size.height / rows.size.toFloat()
            val lineHeight = minOf(
                rowHeight * 0.55f,
                AttachmentPreviewMinimapLineHeight.toPx(),
            ).coerceAtLeast(1f)
            val lineCornerRadius = CornerRadius(
                x = lineHeight / 2f,
                y = lineHeight / 2f,
            )

            rows.forEachIndexed { index, row ->
                if (row.normalizedWidth <= 0f) {
                    return@forEachIndexed
                }

                val lineWidth = (availableWidth * row.normalizedWidth)
                    .coerceAtLeast(1f)
                val lineTop = index * rowHeight + (rowHeight - lineHeight) / 2f
                drawRoundRect(
                    color = lineColor,
                    topLeft = Offset(
                        x = horizontalPadding,
                        y = lineTop,
                    ),
                    size = Size(
                        width = lineWidth,
                        height = lineHeight,
                    ),
                    cornerRadius = lineCornerRadius,
                )
            }

            val currentViewport = viewport ?: return@Canvas
            val viewportMinHeight = AttachmentPreviewMinimapViewportMinHeight
                .toPx()
                .coerceAtMost(size.height)
            val viewportFraction = currentViewport.endFraction - currentViewport.startFraction
            val viewportHeight = (viewportFraction * size.height)
                .coerceAtLeast(viewportMinHeight)
                .coerceAtMost(size.height)
            val viewportTop = (currentViewport.startFraction * size.height)
                .coerceIn(0f, (size.height - viewportHeight).coerceAtLeast(0f))
            val viewportSize = Size(
                width = size.width,
                height = viewportHeight,
            )
            val viewportOffset = Offset(
                x = 0f,
                y = viewportTop,
            )

            drawRoundRect(
                color = viewportColor,
                topLeft = viewportOffset,
                size = viewportSize,
            )
        }
    }
}

private data class AttachmentPreviewMinimapViewport(
    val startFraction: Float,
    val endFraction: Float,
)

private fun LazyListState.attachmentPreviewMinimapViewport(
    lineCount: Int,
): AttachmentPreviewMinimapViewport? {
    if (lineCount <= 0) {
        return null
    }

    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) {
        return null
    }

    val firstVisibleLineIndex = visibleItems
        .first()
        .index
        .coerceIn(0, lineCount - 1)
    val lastVisibleLineIndex = visibleItems
        .last()
        .index
        .coerceIn(firstVisibleLineIndex, lineCount - 1)

    return AttachmentPreviewMinimapViewport(
        startFraction = firstVisibleLineIndex.toFloat() / lineCount.toFloat(),
        endFraction = (lastVisibleLineIndex + 1).toFloat() / lineCount.toFloat(),
    )
}

internal val AttachmentPreviewMinimapVisibleMinWidth = 360.dp
internal val AttachmentPreviewMinimapMinWidth = 48.dp
internal val AttachmentPreviewMinimapMaxWidth = 112.dp
internal val AttachmentPreviewMinimapMaxRowPitch = 4.dp

private val AttachmentPreviewMinimapMinRowHeight = 2.dp
private val AttachmentPreviewMinimapLineHeight = 1.5.dp
private val AttachmentPreviewMinimapHorizontalPadding = 8.dp
private val AttachmentPreviewMinimapViewportMinHeight = 24.dp
private val AttachmentPreviewMinimapViewportCornerRadius = 3.dp
