@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.artemchep.keyguard.feature.home.vault.model.TYPE_CHIPS
import com.artemchep.keyguard.feature.home.vault.model.folderFilters
import com.artemchep.keyguard.feature.home.vault.model.typeFilters
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.assertFocusedFullyInView
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.nodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Arrow keys between the wide vault list and its filter panel. */
class VaultListFilterKeyboardSceneTest {
    @Test
    fun leftFromARowFocusesTheFirstFilterAndRightAtTheLineEndReturns() = withVaultFilters {
        focusSecondRow()
        press(Key.DirectionLeft)
        assertEquals(listOf(TYPE_CHIPS.first()), scene.focusedTexts())

        focusLineEnd()
        press(Key.DirectionRight)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
    }

    @Test
    fun leftAgainFocusesTheFilterFocusedLast() = withVaultFilters {
        focusSecondRow()
        press(Key.DirectionLeft)
        val lineEnd = focusLineEnd()
        press(Key.DirectionRight)
        // From another row.
        press(Key.DirectionUp)
        assertEquals(listOf(FIRST_ROW), scene.focusedTexts())

        press(Key.DirectionLeft)
        assertEquals(listOf(lineEnd), scene.focusedTexts())
    }

    @Test
    fun leftFromARowBelowTheFiltersSkipsTheRail() = withVaultFilters(
        rows = (1..DEEP_ROWS).map { "Row $it" },
        rail = true,
    ) {
        // The first row below every chip, so only the rail is level with it.
        val chipsBottom = TYPE_CHIPS.maxOf { title -> scene.nodeWithText(title).boundsInRoot.bottom }
        val rowIndex = (1..DEEP_ROWS).indexOfFirst { scene.nodeWithText("Row $it").boundsInRoot.top >= chipsBottom }
        assertTrue(rowIndex >= 0, "No row below the chips")
        clickSearchField()
        repeat(rowIndex + 1) { press(Key.DirectionDown) }
        assertEquals(listOf("Row ${rowIndex + 1}"), scene.focusedTexts())

        press(Key.DirectionLeft)
        assertEquals(listOf(TYPE_CHIPS.first()), scene.focusedTexts())

        // The start edge of the filters leads to the rail.
        press(Key.DirectionLeft)
        assertEquals(listOf(RAIL), scene.focusedTexts())
    }

    @Test
    fun aListThatEmptiesAndRefillsLeavesFocusOnTheToggledFilter() = withVaultFilters {
        focusSecondRow()
        press(Key.DirectionLeft)
        val chip = TYPE_CHIPS.first()

        press(Key.Enter)
        assertFalse(hasText(SECOND_ROW))
        assertEquals(listOf(chip), scene.focusedTexts())
        assertEquals(ToggleableState.On, scene.focused().getOrNull(SemanticsProperties.ToggleableState))

        press(Key.Enter)
        assertTrue(hasText(SECOND_ROW))
        assertEquals(listOf(chip), scene.focusedTexts())
        assertEquals(ToggleableState.Off, scene.focused().getOrNull(SemanticsProperties.ToggleableState))
    }

    @Test
    fun rightScrollsBackToARowTheToggleScrolledAway() = withVaultFilters(
        rows = (1..MANY_ROWS).map { "Row $it" },
        // Every row matches, so the toggle only scrolls the list to the top.
        matches = { _, _ -> true },
    ) {
        clickSearchField()
        repeat(FAR_ROW) { press(Key.DirectionDown) }
        assertEquals(listOf(FAR_ROW_TITLE), scene.focusedTexts())

        press(Key.DirectionLeft)
        assertEquals(listOf(TYPE_CHIPS.first()), scene.focusedTexts())
        press(Key.Enter)
        assertTrue(hasText("Row 1"), "The list did not scroll to the top")
        assertFalse(hasText(FAR_ROW_TITLE), "The list did not scroll to the top")

        focusLineEnd()
        press(Key.DirectionRight)
        assertEquals(listOf(FAR_ROW_TITLE), scene.focusedTexts())
        scene.assertFocusedFullyInView()
    }

    @Test
    fun rightAfterTheToggleRemovedTheRowFocusesTheFirstRow() = withVaultFilters(
        // A checked chip hides the second row only.
        matches = { row, checked -> row != SECOND_ROW || checked.isEmpty() },
    ) {
        focusSecondRow()
        press(Key.DirectionLeft)
        press(Key.Enter)
        assertFalse(hasText(SECOND_ROW))
        assertTrue(hasText(FIRST_ROW))

        focusLineEnd()
        press(Key.DirectionRight)
        assertEquals(listOf(FIRST_ROW), scene.focusedTexts())
    }

    @Test
    fun rightFromAFolderChevronReturnsToTheRow() = withVaultFilters(folders = true) {
        focusSecondRow()
        press(Key.DirectionLeft)
        pressUntilFocused(Key.DirectionDown, FOLDER)

        press(Key.DirectionRight)
        assertEquals(emptyList(), scene.focusedTexts())
        assertEquals(listOf("Expand $FOLDER"), scene.focused()[SemanticsProperties.ContentDescription])

        press(Key.Enter)
        assertTrue(hasText(SUBFOLDER))
        assertEquals(listOf("Collapse $FOLDER"), scene.focused()[SemanticsProperties.ContentDescription])

        // The chevron is at the end edge.
        press(Key.DirectionRight)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
    }

    @Test
    fun leftFromARowOfANarrowListStaysOnTheRow() = withVaultFilters(
        width = NavigationComposeHarness.NARROW_WIDTH,
        height = NavigationComposeHarness.NARROW_HEIGHT,
    ) {
        // The narrow list shows no filter panel.
        assertFalse(hasText(TYPE_CHIPS.first()))
        focusSecondRow()

        press(Key.DirectionLeft)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
    }
}

private const val FOLDER = "Work"

private const val SUBFOLDER = "Docs"

private const val DEEP_ROWS = 12

private const val MANY_ROWS = 40

/** Far below the first screen of rows. */
private const val FAR_ROW = 30

private const val FAR_ROW_TITLE = "Row $FAR_ROW"

/**
 * Runs the [block] on a vault list of the [rows] with a flow section
 * of [TYPE_CHIPS], and with [folders] a list section of [FOLDER] › [SUBFOLDER].
 * A chip toggles itself and, like the vault, bumps the revision of the
 * list. A row shows while it [matches] the checked chips, by default
 * while none is checked.
 */
private fun withVaultFilters(
    rows: List<String> = listOf(FIRST_ROW, SECOND_ROW),
    matches: (row: String, checked: Set<String>) -> Boolean = { _, checked -> checked.isEmpty() },
    folders: Boolean = false,
    rail: Boolean = false,
    width: Int = NavigationComposeHarness.WIDE_WIDTH,
    height: Int = NavigationComposeHarness.WIDE_HEIGHT,
    block: NavigationComposeHarness.() -> Unit,
) {
    val checked = mutableStateOf(emptySet<String>())
    val revision = mutableIntStateOf(0)
    val onToggle: (String) -> Unit = { title ->
        checked.value = if (title in checked.value) checked.value - title else checked.value + title
        revision.intValue += 1
    }
    withVaultList(
        width = width,
        height = height,
        filters = {
            typeFilters(checked = checked.value, onClick = onToggle) +
                folderFilters(FOLDER, SUBFOLDER).takeIf { folders }.orEmpty()
        },
        revision = { revision.intValue },
        rail = rail,
        rows = { open ->
            rows
                .filter { row -> matches(row, checked.value) }
                .map { buttonRow(it, open) }
        },
        block = block,
    )
}

/** Moves focus from the first chip to the end of its line. Returns its title. */
private fun NavigationComposeHarness.focusLineEnd(): String {
    val top = scene.nodeWithText(TYPE_CHIPS.first()).boundsInRoot.top
    val line = TYPE_CHIPS.takeWhile { title -> scene.nodeWithText(title).boundsInRoot.top == top }
    assertTrue(line.size >= 2, "First line: $line")
    repeat(line.size - 1) { press(Key.DirectionRight) }
    assertEquals(listOf(line.last()), scene.focusedTexts())
    return line.last()
}

/** Presses the [key] until the filter with the [title] has focus. */
private fun NavigationComposeHarness.pressUntilFocused(key: Key, title: String) {
    repeat(TYPE_CHIPS.size) {
        if (scene.focusedTexts() == listOf(title)) return
        press(key)
    }
    assertEquals(listOf(title), scene.focusedTexts())
}
