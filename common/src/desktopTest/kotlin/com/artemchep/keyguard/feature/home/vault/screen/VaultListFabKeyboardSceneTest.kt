@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.LayoutDirection
import com.artemchep.keyguard.feature.home.vault.model.TYPE_CHIPS
import com.artemchep.keyguard.feature.home.vault.model.typeFilters
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.assertFocusedFullyInView
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Arrow keys on the FABs on top of the vault list. */
class VaultListFabKeyboardSceneTest {
    @Test
    fun downFromTheLastRowFocusesAFabAndUpReturnsToTheRow() = forEachVaultListSize(fabs = true) { size ->
        val fabs = vaultFabs().map { it.id }
        focusSecondRow()

        press(Key.DirectionDown)
        assertTrue(scene.focusedNode().id in fabs, size)
        press(Key.DirectionUp)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts(), size)
    }

    @Test
    fun downOnAFabDoesNothing() = forEachVaultListSize(fabs = true) { size ->
        val fabs = vaultFabs().map { it.id }
        focusSecondRow()
        press(Key.DirectionDown)
        val fab = scene.focusedNode().id
        assertTrue(fab in fabs, size)

        press(Key.DirectionDown)
        assertEquals(fab, scene.focusedNode().id, size)
    }

    @Test
    fun leftAndRightMoveBetweenTheFabsAndStopAtTheEnds() = forEachVaultListSize(fabs = true) { size ->
        val (history, newItem) = vaultFabs().map { it.id }
        focusSecondRow()
        press(Key.DirectionDown)

        press(Key.DirectionRight)
        assertEquals(newItem, scene.focusedNode().id, size)
        press(Key.DirectionRight)
        assertEquals(newItem, scene.focusedNode().id, size)
        press(Key.DirectionLeft)
        assertEquals(history, scene.focusedNode().id, size)
        press(Key.DirectionLeft)
        assertEquals(history, scene.focusedNode().id, size)
    }

    @Test
    fun leftAndRightOnTheFabsSkipTheFiltersTheRailAndTheItem() = withVaultList(
        width = NavigationComposeHarness.WIDE_WIDTH,
        height = NavigationComposeHarness.WIDE_HEIGHT,
        filters = { typeFilters(titles = TYPE_CHIPS.take(2)) },
        focusFirstField = false,
        rail = true,
        fabs = true,
    ) {
        val (history, newItem) = vaultFabs().map { it.id }
        assertTrue(hasText(TYPE_CHIPS.first()))
        focusSecondRow()
        // The item opens next to the list, focus stays on the row.
        press(Key.Enter)
        assertTrue(hasText(FIELD_VALUE))
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
        press(Key.DirectionDown)

        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals(newItem, scene.focusedNode().id)
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals(history, scene.focusedNode().id)

        press(Key.DirectionUp)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
    }

    @Test
    fun leftAndRightFollowTheLayoutDirection() = withVaultList(
        width = NavigationComposeHarness.NARROW_WIDTH,
        height = NavigationComposeHarness.NARROW_HEIGHT,
        layoutDirection = LayoutDirection.Rtl,
        fabs = true,
    ) {
        val (history, newItem) = vaultFabs().map { it.id }
        focusSecondRow()
        press(Key.DirectionDown)

        // The end is on the left.
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals(newItem, scene.focusedNode().id)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals(history, scene.focusedNode().id)
    }

    @Test
    fun upFromAFabScrollsBackToTheRowFocusedLast() {
        val revision = mutableIntStateOf(0)
        withVaultList(
            width = NavigationComposeHarness.NARROW_WIDTH,
            height = NavigationComposeHarness.NARROW_HEIGHT,
            revision = { revision.intValue },
            fabs = true,
            rows = { open -> (1..MANY_ROWS).map { buttonRow("Row $it", open) } },
        ) {
            val fabs = vaultFabs().map { it.id }
            clickSearchField()
            repeat(MANY_ROWS) { press(Key.DirectionDown) }
            assertEquals(listOf(LAST_ROW), scene.focusedTexts())
            // The FABs cover the last row, so Down finds nothing.
            press(Key.Tab)
            assertTrue(scene.focusedNode().id in fabs)

            // Like a new list, which scrolls to the top.
            revision.intValue += 1
            repeat(2) { frame() }
            assertFalse(hasText(LAST_ROW), "The list did not scroll to the top")

            press(Key.DirectionUp)
            assertEquals(listOf(LAST_ROW), scene.focusedTexts())
            scene.assertFocusedFullyInView()
        }
    }
}

private const val MANY_ROWS = 20

private const val LAST_ROW = "Row $MANY_ROWS"
