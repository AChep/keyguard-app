@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.LayoutDirection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.nodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

/** Arrow keys between the search field and the rows of the vault list. */
class VaultListKeyboardSceneTest {
    @Test
    fun downFromTheHeaderSearchFieldEntersTheListAndUpReturns() = withVaultList(
        width = NavigationComposeHarness.WIDE_WIDTH,
        height = NavigationComposeHarness.WIDE_HEIGHT,
    ) {
        assertDownAndUpBetweenFieldAndRows()
    }

    @Test
    fun downFromTheToolbarSearchFieldEntersTheListAndUpReturns() = withVaultList(
        width = NavigationComposeHarness.NARROW_WIDTH,
        height = NavigationComposeHarness.NARROW_HEIGHT,
    ) {
        assertDownAndUpBetweenFieldAndRows()
    }

    @Test
    fun arrowKeysCrossASection() = forEachVaultListSize(
        rows = { open ->
            listOf(
                buttonRow(FIRST_ROW, open),
                VaultItem2.Section(id = SECTION, text = TextHolder.Value(SECTION), caps = false),
                buttonRow(SECOND_ROW, open),
            )
        },
    ) { size ->
        assertTrue(hasText(SECTION), size)
        clickSearchField()
        press(Key.DirectionDown)
        assertEquals(listOf(FIRST_ROW), scene.focusedTexts(), size)

        press(Key.DirectionDown)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts(), size)
        press(Key.DirectionUp)
        assertEquals(listOf(FIRST_ROW), scene.focusedTexts(), size)
    }

    @Test
    fun upFromTheTopRowWithAQueryReturnsToTheToolbarSearchField() = withVaultList(
        width = NavigationComposeHarness.NARROW_WIDTH,
        height = NavigationComposeHarness.NARROW_HEIGHT,
        // The toolbar shows the Clear and Filter buttons.
        query = QUERY,
    ) {
        clickSearchField()
        press(Key.DirectionDown)
        assertEquals(listOf(FIRST_ROW), scene.focusedTexts())

        press(Key.DirectionUp)
        assertSearchFieldFocused()
    }

    @Test
    fun quickFiltersSitBetweenTheToolbarSearchFieldAndTheRows() = withQuickFilters {
        assertDownAndUpThroughTheQuickFilters()
    }

    @Test
    fun downFromTheToolbarSearchFieldFocusesTheFirstQuickFilterRightToLeft() = withQuickFilters(
        layoutDirection = LayoutDirection.Rtl,
    ) {
        // The first chip is on the right, so the top-left chip is the last one.
        val first = scene.nodeWithText(FIRST_CHIP).boundsInRoot
        val second = scene.nodeWithText(SECOND_CHIP).boundsInRoot
        assertTrue(first.left > second.left, "Not right to left: $first, $second")

        assertDownAndUpThroughTheQuickFilters()
    }

    @Test
    fun rowsThatRefillLeaveFocusInTheSearchField() {
        var shown by mutableStateOf(true)
        withVaultList(
            width = NavigationComposeHarness.NARROW_WIDTH,
            height = NavigationComposeHarness.NARROW_HEIGHT,
            rows = { open ->
                listOf(FIRST_ROW, SECOND_ROW)
                    .takeIf { shown }
                    .orEmpty()
                    .map { buttonRow(it, open) }
            },
        ) {
            clickSearchField()
            press(Key.DirectionDown)
            assertEquals(listOf(FIRST_ROW), scene.focusedTexts())
            // The list remembers the row.
            press(Key.DirectionUp)
            assertSearchFieldFocused()

            // Like a query that matches nothing, then something.
            shown = false
            repeat(2) { frame() }
            assertFalse(hasText(FIRST_ROW))
            shown = true
            repeat(2) { frame() }
            assertTrue(hasText(FIRST_ROW))
            assertSearchFieldFocused()
        }
    }

    @Test
    fun escapeOnARowWithAQueryFocusesTheSearchField() = forEachVaultListSize(
        query = QUERY,
    ) { size ->
        clickSearchField()
        press(Key.DirectionDown)
        assertEquals(listOf(FIRST_ROW), scene.focusedTexts(), size)

        // Back would clear the query, which the test state ignores.
        press(Key.Escape)
        assertSearchFieldFocused(size)
    }
}

private const val SECTION = "Group"

private const val QUERY = "a"

private const val FIRST_CHIP = "Logins"

private const val SECOND_CHIP = "Cards"

/** A custom filter, the narrow list shows it as a quick filter chip. */
private fun quickFilter(title: String) = FilterItem.ChipItem(
    sectionId = "custom",
    filterSectionId = "custom",
    filter = FilterItem.Item.Filter.Toggle(
        filters = setOf(DFilter.ByType(DSecret.Type.Login)),
        id = title,
    ),
    leading = null,
    title = title,
    text = null,
    onClick = {},
    checked = false,
)

/** A narrow vault list with the quick filters [FIRST_CHIP] and [SECOND_CHIP] above two rows. */
private fun withQuickFilters(
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    block: NavigationComposeHarness.() -> Unit,
) = withVaultList(
    width = NavigationComposeHarness.NARROW_WIDTH,
    height = NavigationComposeHarness.NARROW_HEIGHT,
    layoutDirection = layoutDirection,
    filters = { listOf(FIRST_CHIP, SECOND_CHIP).map(::quickFilter) },
    rows = { open ->
        listOf(
            VaultItem2.QuickFilters(id = "quick_filters", items = persistentListOf()),
            buttonRow(FIRST_ROW, open),
            buttonRow(SECOND_ROW, open),
        )
    },
    block = block,
)

/**
 * Down from the search field focuses the first chip, then the first row.
 * Up goes back through a chip to the search field.
 */
private fun NavigationComposeHarness.assertDownAndUpThroughTheQuickFilters() {
    clickSearchField()
    press(Key.DirectionDown)
    assertEquals(listOf(FIRST_CHIP), scene.focusedTexts())
    press(Key.DirectionDown)
    assertEquals(listOf(FIRST_ROW), scene.focusedTexts())

    // The 2D search picks the chip nearest to the middle of the row.
    press(Key.DirectionUp)
    val chip = scene.focusedTexts()
    assertTrue(chip == listOf(FIRST_CHIP) || chip == listOf(SECOND_CHIP), "Not a chip: $chip")
    press(Key.DirectionUp)
    assertSearchFieldFocused()
}

private fun NavigationComposeHarness.assertDownAndUpBetweenFieldAndRows() {
    clickSearchField()
    assertEquals(InputMode.Touch, scene.inputMode)

    press(Key.DirectionDown)
    assertEquals(listOf(FIRST_ROW), scene.focusedTexts())
    assertEquals(InputMode.Keyboard, scene.inputMode)
    press(Key.DirectionDown)
    assertEquals(listOf(SECOND_ROW), scene.focusedTexts())

    press(Key.DirectionUp)
    press(Key.DirectionUp)
    assertSearchFieldFocused()
}
