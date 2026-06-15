package com.artemchep.keyguard.feature.attachmentpreview.minimap

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewLineIndex

internal data class AttachmentPreviewMinimapRow(
    val startLineIndex: Int,
    val endLineIndexExclusive: Int,
    val normalizedWidth: Float,
)

internal fun buildAttachmentPreviewMinimapRows(
    lineIndex: AttachmentPreviewLineIndex,
    maxRows: Int,
): List<AttachmentPreviewMinimapRow> {
    val lineCount = lineIndex.size
    if (lineCount == 0) {
        return emptyList()
    }

    val rowCount = maxRows
        .coerceAtLeast(1)
        .coerceAtMost(lineCount)
    val maxLineLength = lineIndex.maxLineLength
        .coerceAtLeast(1)

    return List(rowCount) { rowIndex ->
        val startLineIndex = rowIndex * lineCount / rowCount
        val endLineIndexExclusive = ((rowIndex + 1) * lineCount / rowCount)
            .coerceAtLeast(startLineIndex + 1)
            .coerceAtMost(lineCount)
        val rowLineLength = maxLineLength(
            lineIndex = lineIndex,
            startLineIndex = startLineIndex,
            endLineIndexExclusive = endLineIndexExclusive,
        )
        AttachmentPreviewMinimapRow(
            startLineIndex = startLineIndex,
            endLineIndexExclusive = endLineIndexExclusive,
            normalizedWidth = rowLineLength.toFloat() / maxLineLength.toFloat(),
        )
    }
}

internal fun attachmentPreviewMinimapLineIndexAtOffset(
    lineCount: Int,
    offsetY: Float,
    height: Float,
): Int {
    if (lineCount <= 1 || height <= 0f) {
        return 0
    }

    val normalizedOffset = (offsetY / height)
        .coerceIn(0f, 0.999_999f)
    return (normalizedOffset * lineCount.toFloat())
        .toInt()
        .coerceIn(0, lineCount - 1)
}

internal fun attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
    lineCount: Int,
    pointerLineIndex: Int,
    visibleLineCount: Int,
): Int {
    if (lineCount <= 1) {
        return 0
    }

    val coercedPointerLineIndex = pointerLineIndex
        .coerceIn(0, lineCount - 1)
    val centerOffset = visibleLineCount
        .coerceAtLeast(1) / 2
    return (coercedPointerLineIndex - centerOffset)
        .coerceIn(0, lineCount - 1)
}

internal fun attachmentPreviewMinimapBucketedMaxRows(
    maxRows: Int,
): Int {
    val coercedMaxRows = maxRows.coerceAtLeast(1)
    if (coercedMaxRows <= AttachmentPreviewMinimapExactRowBucketLimit) {
        return coercedMaxRows
    }

    val bucket = coercedMaxRows / AttachmentPreviewMinimapRowBucketSize
    return bucket * AttachmentPreviewMinimapRowBucketSize
}

internal fun attachmentPreviewMinimapPanelHeightPx(
    fullHeightPx: Float,
    lineCount: Int,
    maxRowPitchPx: Float,
): Float {
    if (
        lineCount <= 0 ||
        fullHeightPx <= 0f ||
        maxRowPitchPx <= 0f ||
        fullHeightPx.isNaN() ||
        maxRowPitchPx.isNaN()
    ) {
        return 0f
    }

    return minOf(
        fullHeightPx,
        lineCount.toFloat() * maxRowPitchPx,
    )
}

private fun maxLineLength(
    lineIndex: AttachmentPreviewLineIndex,
    startLineIndex: Int,
    endLineIndexExclusive: Int,
): Int {
    var maxLineLength = 0
    for (index in startLineIndex until endLineIndexExclusive) {
        maxLineLength = maxOf(
            maxLineLength,
            lineIndex.lineLengthAt(index),
        )
    }

    return maxLineLength
}

private const val AttachmentPreviewMinimapExactRowBucketLimit = 128
private const val AttachmentPreviewMinimapRowBucketSize = 16
