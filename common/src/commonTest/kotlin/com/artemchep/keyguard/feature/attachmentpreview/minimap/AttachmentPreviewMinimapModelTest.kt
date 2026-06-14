package com.artemchep.keyguard.feature.attachmentpreview.minimap

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewLineIndex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentPreviewMinimapModelTest {
    @Test
    fun `line widths are normalized monotonically`() {
        val rows = "a\nbbbb\ncc".minimapRows(maxRows = 3)

        assertEquals(3, rows.size)
        assertTrue(rows[0].normalizedWidth < rows[2].normalizedWidth)
        assertTrue(rows[2].normalizedWidth < rows[1].normalizedWidth)
        assertEquals(1f, rows[1].normalizedWidth)
    }

    @Test
    fun `empty lines keep zero minimap width`() {
        val rows = "\nabc".minimapRows(maxRows = 2)

        assertEquals(0f, rows[0].normalizedWidth)
        assertEquals(1f, rows[1].normalizedWidth)
    }

    @Test
    fun `large line counts are bucketed into bounded rows`() {
        val rows = (1..10)
            .joinToString(separator = "\n") { index ->
                "x".repeat(index)
            }
            .minimapRows(maxRows = 4)

        assertEquals(4, rows.size)
        assertEquals(0, rows.first().startLineIndex)
        assertEquals(10, rows.last().endLineIndexExclusive)
        rows.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endLineIndexExclusive, next.startLineIndex)
        }
        assertEquals(1f, rows.last().normalizedWidth)
    }

    @Test
    fun `offsets map to target lines`() {
        assertEquals(
            0,
            attachmentPreviewMinimapLineIndexAtOffset(
                lineCount = 10,
                offsetY = 0f,
                height = 100f,
            ),
        )
        assertEquals(
            5,
            attachmentPreviewMinimapLineIndexAtOffset(
                lineCount = 10,
                offsetY = 50f,
                height = 100f,
            ),
        )
        assertEquals(
            9,
            attachmentPreviewMinimapLineIndexAtOffset(
                lineCount = 10,
                offsetY = 100f,
                height = 100f,
            ),
        )
    }

    @Test
    fun `centered viewport target subtracts half of visible lines`() {
        assertEquals(
            40,
            attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
                lineCount = 100,
                pointerLineIndex = 50,
                visibleLineCount = 20,
            ),
        )
    }

    @Test
    fun `centered viewport target clamps at the top`() {
        assertEquals(
            0,
            attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
                lineCount = 100,
                pointerLineIndex = 4,
                visibleLineCount = 20,
            ),
        )
    }

    @Test
    fun `centered viewport target clamps pointer line at the bottom`() {
        assertEquals(
            89,
            attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
                lineCount = 100,
                pointerLineIndex = 120,
                visibleLineCount = 20,
            ),
        )
    }

    @Test
    fun `centered viewport target handles invalid counts`() {
        assertEquals(
            0,
            attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
                lineCount = 0,
                pointerLineIndex = 5,
                visibleLineCount = 20,
            ),
        )
        assertEquals(
            5,
            attachmentPreviewMinimapCenteredFirstVisibleLineIndex(
                lineCount = 10,
                pointerLineIndex = 5,
                visibleLineCount = 0,
            ),
        )
    }

    @Test
    fun `row count bucket keeps small row counts exact`() {
        assertEquals(1, attachmentPreviewMinimapBucketedMaxRows(maxRows = 0))
        assertEquals(64, attachmentPreviewMinimapBucketedMaxRows(maxRows = 64))
        assertEquals(128, attachmentPreviewMinimapBucketedMaxRows(maxRows = 128))
    }

    @Test
    fun `row count bucket floors large row counts to multiples of sixteen`() {
        assertEquals(128, attachmentPreviewMinimapBucketedMaxRows(maxRows = 129))
        assertEquals(144, attachmentPreviewMinimapBucketedMaxRows(maxRows = 159))
        assertEquals(160, attachmentPreviewMinimapBucketedMaxRows(maxRows = 160))
    }

    @Test
    fun `row count bucket never exceeds requested max rows`() {
        val maxRows = 257
        val bucketedMaxRows = attachmentPreviewMinimapBucketedMaxRows(maxRows = maxRows)

        assertTrue(bucketedMaxRows <= maxRows)
    }

    @Test
    fun `minimap panel height shrinks short files to row pitch`() {
        val height = attachmentPreviewMinimapPanelHeightPx(
            fullHeightPx = 300f,
            lineCount = 10,
            maxRowPitchPx = 4f,
        )

        assertEquals(40f, height)
    }

    @Test
    fun `minimap panel height uses full height for dense files`() {
        val height = attachmentPreviewMinimapPanelHeightPx(
            fullHeightPx = 300f,
            lineCount = 100,
            maxRowPitchPx = 4f,
        )

        assertEquals(300f, height)
    }

    @Test
    fun `minimap panel height clamps invalid inputs to zero`() {
        assertEquals(
            0f,
            attachmentPreviewMinimapPanelHeightPx(
                fullHeightPx = 300f,
                lineCount = 0,
                maxRowPitchPx = 4f,
            ),
        )
        assertEquals(
            0f,
            attachmentPreviewMinimapPanelHeightPx(
                fullHeightPx = 0f,
                lineCount = 10,
                maxRowPitchPx = 4f,
            ),
        )
        assertEquals(
            0f,
            attachmentPreviewMinimapPanelHeightPx(
                fullHeightPx = 300f,
                lineCount = 10,
                maxRowPitchPx = 0f,
            ),
        )
    }

    private fun String.minimapRows(
        maxRows: Int,
    ): List<AttachmentPreviewMinimapRow> = buildAttachmentPreviewMinimapRows(
        lineIndex = AttachmentPreviewLineIndex.of(this),
        maxRows = maxRows,
    )
}
