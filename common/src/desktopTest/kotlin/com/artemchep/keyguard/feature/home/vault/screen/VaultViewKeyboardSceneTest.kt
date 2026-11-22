@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.menuOption
import com.artemchep.keyguard.ui.nodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Keyboard focus inside of an item opened from the vault list. */
class VaultViewKeyboardSceneTest {
    @Test
    fun arrowKeysMoveBetweenTheFieldsAndSkipTheOtherRows() = forEachVaultListSize(
        fields = { MIXED_FIELDS },
    ) { size ->
        openSecondItem()
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)

        // Over the label.
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PASSWORD), scene.focusedTexts(), size)
        // Over the section.
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(EMAIL), scene.focusedTexts(), size)
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts(), size)

        press(Key.DirectionUp)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)
    }

    @Test
    fun upFromTheTopFieldEntersTheToolbarAndDownReturns() = forEachVaultListSize(
        fields = { MIXED_FIELDS },
        onEdit = {},
        actions = { OPTIONS },
    ) { size ->
        openSecondItem()
        val username = scene.focusedNode().boundsInRoot

        press(Key.DirectionUp)
        assertEquals(emptyList(), scene.focusedTexts(), size)
        val start = scene.focusedNode().boundsInRoot
        assertTrue(start.bottom <= username.top, "$size: not in the toolbar: $start")

        // The bar enters its start, and stops there.
        press(Key.DirectionLeft)
        assertEquals(start, scene.focusedNode().boundsInRoot, size)

        // Then Edit and the options, and it stops at the end.
        val stops = moveAlongToolbar(Key.DirectionRight)
        assertTrue(stops.size >= 2, "$size: $stops")
        assertTrue(stops.zipWithNext().all { (a, b) -> a.left < b.left }, "$size: $stops")
        assertTrue(stops.all { it.bottom <= username.top }, "$size: left the toolbar: $stops")
        val back = moveAlongToolbar(Key.DirectionLeft)
        assertEquals(stops.reversed(), back, size)

        // Down from the toolbar returns to the field.
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)
    }

    @Test
    fun sideKeysReturnToTheOpenRowAndToTheFieldInTheWideLayout() = withVaultList(
        width = NavigationComposeHarness.WIDE_WIDTH,
        height = NavigationComposeHarness.WIDE_HEIGHT,
        fields = { MIXED_FIELDS },
        rows = { open -> (1..ROW_COUNT).map { buttonRow("Row $it", open) } },
    ) {
        clickSearchField()
        press(Key.DirectionDown)
        assertEquals(listOf("Row 1"), scene.focusedTexts())
        press(Key.Enter)
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts())
        repeat(3) { press(Key.DirectionDown) }
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts())
        // The open row is far above the field.
        val phone = scene.focusedNode().boundsInRoot
        assertTrue(scene.nodeWithText("Row 1").boundsInRoot.bottom < phone.top)

        press(Key.DirectionLeft)
        assertEquals(listOf("Row 1"), scene.focusedTexts())

        press(Key.DirectionRight)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts())
    }

    @Test
    fun closingAnItemOnTopRefocusesTheField() = forEachVaultListSize(
        fields = { MIXED_FIELDS },
    ) { size ->
        openSecondItem()
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PASSWORD), scene.focusedTexts(), size)

        pushItem(OTHER_ITEM)
        assertTrue(hasText(FIELD_VALUE), size)

        press(Key.Escape)
        assertFalse(hasText(FIELD_VALUE), size)
        assertEquals(itemFieldTexts(PASSWORD), scene.focusedTexts(), size)
    }

    @Test
    fun closingTheEditScreenRefocusesEdit() = forEachVaultListSize(
        fields = { MIXED_FIELDS },
        onEdit = { queueRoute(screen(EDIT_SCREEN)) },
    ) { size ->
        openSecondItem()
        press(Key.DirectionUp)
        // With no options, Edit is at the end of the toolbar.
        val edit = moveAlongToolbar(Key.DirectionRight).last()

        press(Key.Enter)
        assertTrue(screens.isLive(EDIT_SCREEN), size)

        press(Key.Escape)
        assertFalse(screens.isLive(EDIT_SCREEN), size)
        assertEquals(edit, scene.focusedNode().boundsInRoot, size)
        press(Key.Enter)
        assertTrue(screens.isLive(EDIT_SCREEN), "$size: focus was not on Edit")
    }

    @Test
    fun closingAScreenOpenedFromTheOptionsRefocusesTheOptions() = forEachVaultListSize(
        fields = { MIXED_FIELDS },
        onEdit = {},
        actions = {
            listOf(
                FlatItemAction(
                    title = TextHolder.Value(HISTORY_ACTION),
                    onClick = { queueRoute(screen(HISTORY_SCREEN)) },
                ),
            )
        },
    ) { size ->
        openSecondItem()
        press(Key.DirectionUp)
        val options = moveAlongToolbar(Key.DirectionRight).last()

        press(Key.Enter)
        assertNotNull(scene.menuOption(HISTORY_ACTION), size)
        press(Key.DirectionDown)
        assertFocusedHasText(HISTORY_ACTION, size)
        press(Key.Enter)
        assertTrue(screens.isLive(HISTORY_SCREEN), size)

        press(Key.Escape)
        assertFalse(screens.isLive(HISTORY_SCREEN), size)
        assertEquals(options, scene.focusedNode().boundsInRoot, size)
    }

    @Test
    fun enterOnAFieldOpensItsMenuAndDownFocusesTheFirstAction() = forEachVaultListSize(
        fields = { MIXED_FIELDS },
    ) { size ->
        openSecondItem()
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)

        press(Key.Enter)
        val action = itemFieldAction(USERNAME)
        assertNotNull(scene.menuOption(action), "$size: no menu")

        press(Key.DirectionDown)
        assertFocusedHasText(action, size)
    }
}

private const val PASSWORD = "Password"

private const val EMAIL = "Email"

/** Fields with a section and a label between them, which take no focus. */
private val MIXED_FIELDS = listOf(
    VaultViewItem.Section(id = "section.login", text = "Login"),
    itemField(USERNAME),
    VaultViewItem.Label(id = "label", text = AnnotatedString("A label")),
    itemField(PASSWORD),
    VaultViewItem.Section(id = "section.contact", text = "Contact"),
    itemField(EMAIL),
    itemField(PHONE),
)

private const val ROW_COUNT = 10

private const val EDIT_SCREEN = "Edit screen"

private const val HISTORY_ACTION = "Password history"

private const val HISTORY_SCREEN = "Password history screen"

private val OPTIONS = listOf(FlatItemAction(title = TextHolder.Value(HISTORY_ACTION)))

/** Toolbars have fewer buttons. */
private const val MAX_TOOLBAR_MOVES = 5

/**
 * Presses the [key] until focus stops moving along the toolbar.
 * Returns the bounds of each focused button, the first one too.
 */
private fun NavigationComposeHarness.moveAlongToolbar(key: Key): List<Rect> {
    val stops = mutableListOf(scene.focusedNode().boundsInRoot)
    repeat(MAX_TOOLBAR_MOVES) {
        press(key)
        val bounds = scene.focusedNode().boundsInRoot
        if (bounds == stops.last()) return stops
        stops += bounds
    }
    fail("Focus did not stop: $stops")
}
