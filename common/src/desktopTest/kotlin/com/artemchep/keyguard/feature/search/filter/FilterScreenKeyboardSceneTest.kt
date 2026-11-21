@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.TYPE_CHIPS
import com.artemchep.keyguard.feature.home.vault.model.folderFilters
import com.artemchep.keyguard.feature.home.vault.model.typeFilters
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedNodeOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.withScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Arrow keys inside a bare [FilterScreen]. */
class FilterScreenKeyboardSceneTest {
    @Test
    fun arrowKeysMoveBetweenTheFilters() = withFilterScreen(onFocusContent = null) {
        val lines = chipLines()
        focusTab(lines[0][0])

        // Along a line.
        press(Key.DirectionRight)
        assertEquals(listOf(lines[0][1]), focusedTexts())
        assertEquals(InputMode.Keyboard, inputMode)
        press(Key.DirectionLeft)
        assertEquals(listOf(lines[0][0]), focusedTexts())

        // Between the lines.
        press(Key.DirectionDown)
        assertTrue(focusedTitle() in lines[1], "Not on line 2: ${focusedTexts()}")
        press(Key.DirectionUp)
        assertTrue(focusedTitle() in lines[0], "Not on line 1: ${focusedTexts()}")
    }

    @Test
    fun rightAtTheLineEndSearchesWithoutAContentCallback() = withFilterScreen(onFocusContent = null) {
        val lines = chipLines()
        focusLineEnd(lines[0])

        // The plain search, whatever it finds.
        press(Key.DirectionRight)
        assertNotNull(focusedNodeOrNull())
        assertNotEquals(listOf(lines[0].last()), focusedTexts())
    }

    @Test
    fun rightAtTheLineEndCallsTheContentCallback() {
        var calls = 0
        withFilterScreen(onFocusContent = { calls++ }) {
            val lines = chipLines()
            focusTab(lines[0][0])

            // A chip in the middle of a line.
            press(Key.DirectionRight)
            assertEquals(listOf(lines[0][1]), focusedTexts())
            assertEquals(0, calls)

            focusLineEnd(lines[0])
            press(Key.DirectionRight)
            assertEquals(1, calls)
            assertEquals(listOf(lines[0].last()), focusedTexts())
        }
    }

    @Test
    fun leftAtTheLineEndCallsTheContentCallbackRightToLeft() {
        var calls = 0
        withFilterScreen(onFocusContent = { calls++ }, layoutDirection = LayoutDirection.Rtl) {
            val lines = chipLines()
            focusTab(lines[0][0])

            // The start edge is on the right.
            press(Key.DirectionRight)
            assertEquals(0, calls)
            assertEquals(listOf(lines[0][0]), focusedTexts())

            focusLineEnd(lines[0], towardEnd = Key.DirectionLeft)
            press(Key.DirectionLeft)
            assertEquals(1, calls)
            assertEquals(listOf(lines[0].last()), focusedTexts())
        }
    }

    @Test
    fun rightFromAFolderRowFocusesItsChevron() = listOf(false, true).forEach { withCallback ->
        var calls = 0
        val onFocusContent: (() -> Unit)? = if (withCallback) {
            { calls++ }
        } else {
            null
        }
        withFilterScreen(onFocusContent = onFocusContent) {
            focusTab(FOLDER)
            val row = nodeWithText(FOLDER).boundsInRoot

            press(Key.DirectionRight)
            val chevron = focusedNode().boundsInRoot
            assertEquals(emptyList(), focusedTexts(), "callback $withCallback")
            assertTrue(row.contains(chevron.center) && chevron.width < row.width, "callback $withCallback")

            press(Key.DirectionLeft)
            assertEquals(listOf(FOLDER), focusedTexts(), "callback $withCallback")

            if (withCallback) {
                // The chevron is at the end edge.
                press(Key.DirectionRight)
                press(Key.DirectionRight)
                assertEquals(1, calls)
                assertEquals(chevron, focusedNode().boundsInRoot)
            }
        }
    }

    @Test
    fun theChevronLabelFollowsTheFolder() = withFilterScreen(onFocusContent = null) {
        focusTab(FOLDER)
        press(Key.DirectionRight)
        assertEquals(Role.Button, focused()[SemanticsProperties.Role])
        assertEquals(listOf("Expand $FOLDER"), focused()[SemanticsProperties.ContentDescription])

        press(Key.Enter)
        assertNotNull(nodeWithTextOrNull("Docs"))
        assertEquals(listOf("Collapse $FOLDER"), focused()[SemanticsProperties.ContentDescription])
    }

    @Test
    fun resetFocusesTheFirstFilter() = withFilterScreen(
        onFocusContent = null,
        active = mutableStateOf(true),
    ) {
        focusReset()
        press(Key.Enter)
        assertEquals(listOf(CHIPS.first()), focusedTexts())

        // The FABs leave, the focus stays.
        advanceTimeBy(1_000L)
        assertNull(resetTextOrNull())
        assertEquals(listOf(CHIPS.first()), focusedTexts())
    }
}

private const val PANEL_WIDTH = 220

private const val FOLDER = "Work"

private const val RESET = "Reset"

private val CHIPS = TYPE_CHIPS + listOf("Bank", "License")

/** With [active] the Save and Reset FABs show, and Reset clears it. */
private fun withFilterScreen(
    onFocusContent: (() -> Unit)?,
    active: MutableState<Boolean> = mutableStateOf(false),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    test: ImageComposeScene.() -> Unit,
) = withScene(
    content = {
        Row {
            Box(
                modifier = Modifier
                    .width(PANEL_WIDTH.dp)
                    .fillMaxHeight(),
            ) {
                FilterScreen(
                    count = null,
                    items = filterItems(),
                    onClear = { active.value = false }.takeIf { active.value },
                    onFocusContent = onFocusContent,
                )
            }
            // A target the plain search can find on the end side.
            TextButton(onClick = {}) {
                Text("Content")
            }
        }
    },
    layoutDirection = layoutDirection,
    test = test,
)

/** A flow section of chips on a few lines, and a list section with a nested folder. */
private fun filterItems(): List<FilterItemModel> = typeFilters(titles = CHIPS) + folderFilters(FOLDER, "Docs")

/** The chip titles by line, from where they are placed. */
private fun ImageComposeScene.chipLines(): List<List<String>> {
    val lines = CHIPS
        .groupBy { title -> nodeWithText(title).boundsInRoot.top }
        .toSortedMap()
        .values
        .toList()
    assertTrue(lines.size >= 2 && lines[0].size >= 2, "Chip lines: $lines")
    return lines
}

private fun ImageComposeScene.focusedTitle(): String? = focusedTexts().singleOrNull()

/** Tabs to the filter with the [title], in keyboard input mode. */
private fun ImageComposeScene.focusTab(title: String) {
    repeat(CHIPS.size + 8) {
        if (focusedTitle() == title) return
        press(Key.Tab)
    }
    fail("Tab never reached $title")
}

/** The text of Reset. The FAB clears its semantics, so it is not merged. */
private fun ImageComposeScene.resetTextOrNull(): SemanticsNode? = semanticsOwners
    .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
    .firstOrNull { node -> RESET in node.texts() }

/** Tabs to the FAB around the text of Reset, in keyboard input mode. */
private fun ImageComposeScene.focusReset() {
    val text = assertNotNull(resetTextOrNull()).boundsInRoot
    repeat(CHIPS.size + 8) {
        if (focusedNodeOrNull()?.boundsInRoot?.contains(text.center) == true) return
        press(Key.Tab)
    }
    fail("Tab never reached $RESET")
}

/** Focuses the last chip of the [line] with the [towardEnd] key from its first one. */
private fun ImageComposeScene.focusLineEnd(
    line: List<String>,
    towardEnd: Key = Key.DirectionRight,
) {
    focusTab(line.first())
    repeat(line.size - 1) { press(towardEnd) }
    assertEquals(listOf(line.last()), focusedTexts())
}
