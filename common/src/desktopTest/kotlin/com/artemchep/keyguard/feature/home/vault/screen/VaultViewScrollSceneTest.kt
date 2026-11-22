@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationScrollPosition
import com.artemchep.keyguard.feature.navigation.click
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.assertFocusedFullyInView
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.wheelScrollDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Scroll position of an item opened from the vault list. */
class VaultViewScrollSceneTest {
    @Test
    fun closingAnItemOnTopKeepsTheScrollPosition() = forEachSize { size ->
        val itemEntry = openItemWithAClick(SECOND_ROW, size)
        val saved = scrollItem(size)

        pushItem(OTHER_ITEM)
        assertTrue(hasText(FIELD_VALUE), size)
        pop()

        assertEquals(saved, itemEntry.viewMemory.scrollPosition, size)
        assertFalse(hasText(FIRST_FIELD_VALUE), size)
        assertTrue(hasText(itemFieldValue(assertNotNull(saved.itemKey))), size)
    }

    @Test
    fun anotherItemStartsAtTheTop() = forEachSize { size ->
        openItemWithAClick(SECOND_ROW, size)
        scrollItem(size)

        // The narrow list is under the item.
        if (!hasText(FIRST_ROW)) pop()
        val itemEntry = openItemWithAClick(FIRST_ROW, size)

        val position = assertNotNull(itemEntry.viewMemory.scrollPosition, size)
        assertEquals(0, position.itemIndex, size)
        assertEquals(0, position.itemOffset, size)
        assertEquals(0f, position.toolbarHeightOffset, size)
        assertTrue(hasText(FIRST_FIELD_VALUE), size)
    }

    @Test
    fun theFieldsLoadingAgainDoNotLoseTheScrollPosition() {
        var fields by mutableStateOf(MANY_FIELDS)
        forEachSize(fields = { fields }) { size ->
            fields = MANY_FIELDS
            val itemEntry = openItemWithAClick(SECOND_ROW, size)
            val saved = scrollItem(size)

            // Come back while the fields are not there yet.
            pushItem(OTHER_ITEM)
            fields = emptyList()
            pop()
            assertFalse(hasText(FIELD_VALUE), size)
            assertEquals(saved, itemEntry.viewMemory.scrollPosition, size)

            fields = MANY_FIELDS
            repeat(2) { frame() }
            assertEquals(saved, itemEntry.viewMemory.scrollPosition, size)
            assertFalse(hasText(FIRST_FIELD_VALUE), size)
            assertTrue(hasText(itemFieldValue(assertNotNull(saved.itemKey))), size)
        }
    }

    @Test
    fun closingAnItemOnTopRefocusesAFarFieldWithoutAScroll() = forEachSize { size ->
        openSecondItem()
        val itemEntry = router("vault").entries.last()
        repeat(FAR_FIELD - 1) { press(Key.DirectionDown) }
        assertEquals(itemFieldTexts(FAR_FIELD_TITLE), scene.focusedTexts(), size)
        val saved = assertNotNull(itemEntry.viewMemory.scrollPosition, size)
        assertTrue(saved.itemIndex > 0, "$size: the item did not scroll: $saved")
        assertFalse(hasText(FIRST_FIELD_VALUE), size)

        pushItem(OTHER_ITEM)
        assertTrue(hasText(FIELD_VALUE), size)
        press(Key.Escape)
        assertFalse(hasText(FIELD_VALUE), size)
        assertEquals(itemFieldTexts(FAR_FIELD_TITLE), scene.focusedTexts(), size)
        scene.assertFocusedFullyInView()
        assertEquals(saved, itemEntry.viewMemory.scrollPosition, size)
    }
}

private const val FIELD_COUNT = 40

/** Below the first screen of fields. */
private const val FAR_FIELD = 20

private const val FAR_FIELD_TITLE = "Field $FAR_FIELD"

private val MANY_FIELDS = (1..FIELD_COUNT).map { itemField("Field $it") }

private val FIRST_FIELD_VALUE = itemFieldValue("Field 1")

private const val SCROLL_STEPS = 14

/** Runs the [block] on a wide and on a narrow vault list, its items have the [fields]. */
private fun forEachSize(
    fields: () -> List<VaultViewItem> = { MANY_FIELDS },
    block: NavigationComposeHarness.(size: String) -> Unit,
) = forEachVaultListSize(fields = fields, block = block)

/**
 * Opens the item of the [row] with a click, so the scene is in touch
 * input mode and nothing takes focus. Returns the entry of the item.
 */
private fun NavigationComposeHarness.openItemWithAClick(row: String, size: String): NavigationEntry {
    click(scene.nodeWithText(row).boundsInRoot.center)
    assertEquals(InputMode.Touch, inputMode, size)
    assertTrue(hasText(FIRST_FIELD_VALUE), size)
    return router("vault").entries.last()
}

/** Scrolls the open item down with the mouse wheel. Returns its saved position. */
private fun NavigationComposeHarness.scrollItem(size: String): NavigationScrollPosition {
    val itemEntry = router("vault").entries.last()
    // The item is the only pane in a narrow scene, and the end one in a wide scene.
    val bounds = scene.nodeWithText(FIRST_FIELD_VALUE).boundsInRoot
    scene.wheelScrollDown(Offset(bounds.center.x, bounds.bottom), steps = SCROLL_STEPS)
    frame()
    val saved = assertNotNull(itemEntry.viewMemory.scrollPosition, size)
    assertTrue(saved.itemIndex > 0, "$size: the item did not scroll: $saved")
    assertFalse(hasText(FIRST_FIELD_VALUE), size)
    return saved
}
