package com.artemchep.keyguard.feature.filepicker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePickerEffectTest {
    @Test
    fun `maps png mime type to png extension`() {
        assertEquals(
            setOf("png"),
            mimeTypesToExtensions(listOf("image/png")),
        )
    }

    @Test
    fun `maps standard jpeg mime type to jpg and jpeg extensions`() {
        assertEquals(
            setOf("jpg", "jpeg"),
            mimeTypesToExtensions(listOf("image/jpeg")),
        )
    }

    @Test
    fun `maps legacy jpeg mime type to jpg and jpeg extensions`() {
        assertEquals(
            setOf("jpg", "jpeg"),
            mimeTypesToExtensions(listOf("image/jpg")),
        )
    }

    @Test
    fun `deduplicates mixed mime types`() {
        assertEquals(
            setOf("png", "jpg", "jpeg"),
            mimeTypesToExtensions(
                listOf(
                    "image/png",
                    "image/jpeg",
                    "image/jpg",
                    "image/png",
                ),
            ),
        )
    }

    @Test
    fun `returns null when no supported mime types are present`() {
        assertNull(
            mimeTypesToExtensions(listOf("application/json")),
        )
    }
}
