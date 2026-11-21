package com.artemchep.keyguard.feature.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationScrollPositionTest {
    @Test
    fun `resolve returns null when item keys are empty`() {
        val position = NavigationScrollPosition(
            itemKey = "row5",
            itemIndex = 4,
            itemOffset = 32,
        )
        assertNull(position.resolve(emptyList()))
    }

    @Test
    fun `resolve follows saved item key with exact offset when items are inserted above`() {
        val position = NavigationScrollPosition(
            itemKey = "row5",
            itemIndex = 4,
            itemOffset = 37,
        )
        val keys = listOf("banner", "intro", "row1", "row2", "row3", "row4", "row5", "row6")
        assertEquals(
            ResolvedScrollTarget(index = 6, offset = 37),
            position.resolve(keys),
        )
    }

    @Test
    fun `resolve preserves signed negative offset for Wear anchor scrolling`() {
        val position = NavigationScrollPosition(
            itemKey = "security",
            itemIndex = 2,
            itemOffset = -18,
        )
        val keys = listOf("header", "autofill", "security", "display")
        assertEquals(
            ResolvedScrollTarget(index = 2, offset = -18),
            position.resolve(keys),
        )
    }

    @Test
    fun `resolve falls back to clamped index and zero offset when saved item key disappears`() {
        val position = NavigationScrollPosition(
            itemKey = "removed",
            itemIndex = 10,
            itemOffset = 45,
        )
        val keys = listOf("row1", "row2", "row3")
        assertEquals(
            ResolvedScrollTarget(index = 2, offset = 0),
            position.resolve(keys),
        )
    }

    @Test
    fun `resolve clamps negative fallback index to zero`() {
        val position = NavigationScrollPosition(
            itemKey = null,
            itemIndex = -3,
            itemOffset = 20,
        )
        val keys = listOf("row1", "row2")
        assertEquals(
            ResolvedScrollTarget(index = 0, offset = 0),
            position.resolve(keys),
        )
    }
}
