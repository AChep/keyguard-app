@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.quicksearch.createSecret
import com.artemchep.keyguard.feature.home.vault.quicksearch.createVaultItem
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.click
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.nodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening an item from the vault list, and closing it, with the keyboard. */
class VaultListOpenKeyboardSceneTest {
    @Test
    fun enterFocusesTheFirstFieldOfTheItemAndEscapeReturnsToTheRow() = forEachVaultListSize { size ->
        focusSecondRow()

        press(Key.Enter)
        assertTrue(hasText(FIELD_VALUE), size)
        assertEquals(listOf(FIELD_TITLE, FIELD_VALUE), scene.focusedTexts(), size)

        press(Key.Escape)
        assertFalse(hasText(FIELD_VALUE), size)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts(), size)
    }

    @Test
    fun escapeScrollsAFarRowBackIntoView() = withVaultList(
        width = NavigationComposeHarness.NARROW_WIDTH,
        height = NavigationComposeHarness.NARROW_HEIGHT,
        rows = { open -> (1..ROW_COUNT).map { buttonRow("Row $it", open) } },
    ) {
        clickSearchField()
        repeat(FAR_ROW) { press(Key.DirectionDown) }
        assertEquals(listOf(FAR_ROW_TITLE), scene.focusedTexts())
        assertFalse(hasText("Row 1"), "The list did not scroll")

        // The item replaces the list, which forgets its scroll.
        press(Key.Enter)
        assertTrue(hasText(FIELD_VALUE))
        press(Key.Escape)
        assertFalse(hasText(FIELD_VALUE))
        assertEquals(listOf(FAR_ROW_TITLE), scene.focusedTexts())
        val bounds = scene.nodeWithText(FAR_ROW_TITLE).boundsInRoot
        assertTrue(
            bounds.height > 0f && bounds.bottom <= NavigationComposeHarness.NARROW_HEIGHT,
            "The row is off screen: $bounds",
        )
    }

    @Test
    fun deleteOnAnotherRowDoesNotReachTheOpenItem() = withVaultList(
        width = NavigationComposeHarness.WIDE_WIDTH,
        height = NavigationComposeHarness.WIDE_HEIGHT,
        // The row keeps focus when its item opens.
        focusFirstField = false,
        rows = { open -> listOf(FIRST_ITEM, SECOND_ITEM).map { itemRow(it, open) } },
    ) {
        clickSearchField()
        press(Key.DirectionDown)
        assertTrue(itemTitle(FIRST_ITEM) in scene.focusedTexts())
        assertFalse(sendDelete(), "Nothing is open, yet Delete was consumed")

        press(Key.DirectionDown)
        press(Key.Enter)
        assertTrue(hasText(FIELD_VALUE))
        assertTrue(itemTitle(SECOND_ITEM) in scene.focusedTexts())
        assertFalse(sendDelete(), "Delete on the open row was consumed")

        press(Key.DirectionUp)
        assertTrue(itemTitle(FIRST_ITEM) in scene.focusedTexts())
        assertTrue(sendDelete(), "Delete on another row was not consumed")
        assertTrue(hasText(FIELD_VALUE))
    }

    @Test
    fun deleteGoesByTheItemOpenedOnTopOfTheOpenItem() = withVaultList(
        width = NavigationComposeHarness.WIDE_WIDTH,
        height = NavigationComposeHarness.WIDE_HEIGHT,
        // The row keeps focus when its item opens.
        focusFirstField = false,
        rows = { open -> listOf(FIRST_ITEM, SECOND_ITEM).map { itemRow(it, open) } },
    ) {
        clickSearchField()
        press(Key.DirectionDown)
        press(Key.Enter)
        assertTrue(hasText(FIELD_VALUE))
        // Like a link in the first item.
        pushItem(SECOND_ITEM)
        assertEquals(3, router("vault").entries.size)
        assertTrue(itemTitle(FIRST_ITEM) in scene.focusedTexts())

        // The top item would delete the second item.
        assertTrue(sendDelete(), "Delete on the row below the top item was not consumed")
        press(Key.DirectionDown)
        assertTrue(itemTitle(SECOND_ITEM) in scene.focusedTexts())
        assertFalse(sendDelete(), "Delete on the row of the top item was consumed")
    }

    @Test
    fun aTabWhileTheRowsLoadKeepsFocusOffTheFirstField() {
        var fields by mutableStateOf(emptyList<VaultViewItem>())
        withVaultList(
            width = NavigationComposeHarness.NARROW_WIDTH,
            height = NavigationComposeHarness.NARROW_HEIGHT,
            fields = { fields },
        ) {
            focusSecondRow()
            press(Key.Enter)
            assertFalse(hasText(SECOND_ROW))
            assertNull(scene.focusedOrNull())

            // The toolbar Back button.
            press(Key.Tab)
            val back = scene.focusedNode().boundsInRoot
            assertEquals(emptyList(), scene.focusedTexts())

            fields = DEFAULT_FIELDS
            repeat(2) { frame() }
            assertTrue(hasText(FIELD_VALUE))
            assertEquals(back, scene.focusedNode().boundsInRoot)
            press(Key.Enter)
            assertFalse(hasText(FIELD_VALUE), "Focus was not on Back")
        }
    }

    @Test
    fun clickOpensTheItemWithNothingFocused() = forEachVaultListSize { size ->
        click(scene.nodeWithText(SECOND_ROW).boundsInRoot.center)
        assertEquals(InputMode.Touch, scene.inputMode, size)
        assertTrue(hasText(FIELD_VALUE), size)
        assertNull(scene.focusedOrNull(), size)
    }

    @Test
    fun theItemFocusesNothingWithoutTheFlag() = forEachVaultListSize(focusFirstField = false) { size ->
        focusSecondRow()

        press(Key.Enter)
        assertTrue(hasText(FIELD_VALUE), size)
        assertEquals(InputMode.Keyboard, scene.inputMode, size)
        if (hasText(SECOND_ROW)) {
            // The wide list stays next to the item, its row keeps focus.
            assertEquals(listOf(SECOND_ROW), scene.focusedTexts(), size)
        } else {
            // The item replaces the narrow list and its focused row.
            assertNull(scene.focusedOrNull(), size)
        }
    }
}

private const val ROW_COUNT = 40

/** Below the first screen of rows. */
private const val FAR_ROW = 20

private const val FAR_ROW_TITLE = "Row $FAR_ROW"

private const val FIRST_ITEM = "first"

private const val SECOND_ITEM = "second"

/** A cipher row, so the list can tell which row is the open item. */
private fun itemRow(id: String, open: OpenItem) = createVaultItem(id)
    .copy(action = VaultItem2.Item.Action.Go { open(id) })

/** The title of the [itemRow] of the [id]. */
private fun itemTitle(id: String) = createSecret(id = id).name

/** Presses Delete, `true` if the scene consumed it. */
private fun NavigationComposeHarness.sendDelete(): Boolean {
    val consumed = scene.sendKeyEvent(KeyEvent(Key.Delete, KeyEventType.KeyDown))
    scene.sendKeyEvent(KeyEvent(Key.Delete, KeyEventType.KeyUp))
    frame()
    return consumed
}
