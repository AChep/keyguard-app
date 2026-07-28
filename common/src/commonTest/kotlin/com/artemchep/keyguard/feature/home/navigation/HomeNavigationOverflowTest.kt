package com.artemchep.keyguard.feature.home.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeNavigationOverflowTest {
    @Test
    fun `exact fit renders every item`() {
        val result = splitHomeNavigationItemsByMinSize(
            items = listOf("a", "b", "c"),
            availableSizePx = 300,
            itemMinSizePx = 100,
        )

        assertEquals(listOf("a", "b", "c"), result.visible)
        assertTrue(result.overflow.isEmpty())
    }

    @Test
    fun `overflow reserves a slot before showing remaining items`() {
        val result = splitHomeNavigationItemsByMinSize(
            items = listOf("a", "b", "c"),
            availableSizePx = 250,
            itemMinSizePx = 100,
            overflowMinSizePx = 50,
        )

        assertEquals(listOf("a", "b"), result.visible)
        assertEquals(listOf("c"), result.overflow)
    }

    @Test
    fun `very small space sends every item to overflow`() {
        val result = splitHomeNavigationItemsByMinSize(
            items = listOf("a", "b", "c"),
            availableSizePx = 10,
            itemMinSizePx = 100,
        )

        assertTrue(result.visible.isEmpty())
        assertEquals(listOf("a", "b", "c"), result.overflow)
    }

    @Test
    fun `invalid sizes send every item to overflow`() {
        val result = splitHomeNavigationItemsByMinSize(
            items = listOf("a", "b"),
            availableSizePx = 100,
            itemMinSizePx = 0,
        )

        assertTrue(result.visible.isEmpty())
        assertEquals(listOf("a", "b"), result.overflow)
    }
}
