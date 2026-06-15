package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentPreviewImageBoundsTest {
    @Test
    fun `wide image clamps horizontal panning`() {
        val offset = clampAttachmentPreviewImageOffset(
            offset = Offset(1000f, 1000f),
            viewportSize = Size(1000f, 1000f),
            imageSize = Size(2000f, 1000f),
            scale = 2f,
        )

        assertEquals(Offset(500f, 0f), offset)
    }

    @Test
    fun `tall image clamps vertical panning`() {
        val offset = clampAttachmentPreviewImageOffset(
            offset = Offset(-1000f, -1000f),
            viewportSize = Size(1000f, 1000f),
            imageSize = Size(1000f, 2000f),
            scale = 2f,
        )

        assertEquals(Offset(0f, -500f), offset)
    }

    @Test
    fun `viewport filling image clamps both axes`() {
        val offset = clampAttachmentPreviewImageOffset(
            offset = Offset(900f, -900f),
            viewportSize = Size(1000f, 1000f),
            imageSize = Size(1000f, 1000f),
            scale = 2f,
        )

        assertEquals(Offset(500f, -500f), offset)
    }

    @Test
    fun `zooming back to base scale resets offset`() {
        val offset = clampAttachmentPreviewImageOffset(
            offset = Offset(500f, -500f),
            viewportSize = Size(1000f, 1000f),
            imageSize = Size(1000f, 1000f),
            scale = 1f,
        )

        assertEquals(Offset.Zero, offset)
    }

    @Test
    fun `viewport size changes reclamp existing offset`() {
        val offset = clampAttachmentPreviewImageOffset(
            offset = Offset(900f, 900f),
            viewportSize = Size(1600f, 1000f),
            imageSize = Size(1000f, 1000f),
            scale = 3f,
        )

        assertEquals(Offset(700f, 900f), offset)
    }
}
