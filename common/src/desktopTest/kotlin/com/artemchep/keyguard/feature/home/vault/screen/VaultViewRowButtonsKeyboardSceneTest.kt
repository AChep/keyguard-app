@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.click
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.reveal_secure_note
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedNodeOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.nodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

/**
 * Keyboard focus on the rows of an item that have buttons inside of
 * them, opened from the vault list. Right moves from a row along its
 * buttons, Left back.
 */
class VaultViewRowButtonsKeyboardSceneTest {
    @Test
    fun rightMovesFromAFieldAlongItsEyeAndCopyAndLeftMovesBack() = forEachVaultListSize(
        fields = { SECRET_FIELDS },
    ) { size ->
        openSecondItem()
        press(Key.DirectionDown)
        assertFocusedHasText(SECRET, size)

        // The eye, then Copy.
        val buttons = assertRowButtons(count = 2, message = size)
        assertTrue(buttons[0].left < buttons[1].left, "$size: $buttons")

        // Up and Down from the row still move between the rows.
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts(), size)
        press(Key.DirectionUp)
        assertFocusedHasText(SECRET, size)
        press(Key.DirectionUp)
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)

        // So do they from a button inside of the row.
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals(buttons[1], scene.focusedNode().boundsInRoot, size)
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts(), size)
    }

    @Test
    fun leftFromAFieldWithButtonsReturnsToTheOpenRowInTheWideLayout() = withVaultList(
        width = NavigationComposeHarness.WIDE_WIDTH,
        height = NavigationComposeHarness.WIDE_HEIGHT,
        fields = { SECRET_FIELDS },
    ) {
        openSecondItem()
        press(Key.DirectionDown)
        assertFocusedHasText(SECRET)

        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertFocusedHasText(SECRET)
        press(Key.DirectionLeft)
        assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
        press(Key.DirectionRight)
        assertFocusedHasText(SECRET)
    }

    @Test
    fun rightMovesFromEachRowAlongItsButtons() = forEachVaultListSize(
        fields = { ROWS_WITH_BUTTONS },
    ) { size ->
        openSecondItem()
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)

        // Copy, then the reference chip.
        press(Key.DirectionDown)
        assertFocusedHasText(LINKED, size)
        assertRowButtons(count = 2, message = "$size: $LINKED") { index ->
            if (index == 1) {
                assertFocusedHasText(REFERENCE_FIELD, size)
            }
        }

        // The eye, then Copy.
        press(Key.DirectionDown)
        assertFocusedHasText(CARDHOLDER, size)
        assertRowButtons(count = 2, message = "$size: card")

        // The chips of the parent folders.
        press(Key.DirectionDown)
        assertEquals(listOf(FOLDER), scene.focusedTexts(), size)
        assertRowButtons(count = 2, message = "$size: folder") { index ->
            val folder = listOf(ROOT_FOLDER, PARENT_FOLDER)[index]
            assertEquals(listOf(folder), scene.focusedTexts(), size)
        }

        // Use.
        press(Key.DirectionDown)
        assertFocusedHasText(RP_ID, size)
        assertRowButtons(count = 1, message = "$size: passkey")

        // Launch, then Download.
        press(Key.DirectionDown)
        assertFocusedHasText(ATTACHMENT, size)
        assertRowButtons(count = 2, message = "$size: attachment")

        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts(), size)
    }

    @Test
    fun theEyeOfAFieldWithoutAMenuIsARowOfItsOwn() = forEachVaultListSize(
        fields = { PLAIN_SECRET_FIELDS },
    ) { size ->
        openSecondItem()
        val username = scene.focusedNode().boundsInRoot
        val phone = scene.nodeWithText(PHONE).boundsInRoot

        // The field takes no focus, its eye does.
        press(Key.DirectionDown)
        assertEquals(emptyList(), scene.focusedTexts(), size)
        val eye = scene.focusedNode().boundsInRoot
        assertTrue(eye.top >= username.bottom && eye.bottom <= phone.top, "$size: $eye")
        press(Key.DirectionRight)
        assertEquals(eye, scene.focusedNode().boundsInRoot, size)

        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts(), size)
        press(Key.DirectionUp)
        assertEquals(eye, scene.focusedNode().boundsInRoot, size)
        press(Key.DirectionUp)
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)
    }

    @Test
    fun enterOnRevealFocusesTheNote() = forEachVaultListSize(
        fields = { NOTE_FIELDS },
    ) { size ->
        openSecondItem()
        press(Key.DirectionDown)
        assertEquals(listOf(revealSecureNote()), scene.focusedTexts(), size)
        assertFalse(hasText(NOTE_TEXT), size)

        press(Key.Enter)
        assertTrue(hasText(NOTE_TEXT), size)
        // The note's text has focus. Unlike a row, it
        // has no focused semantics node.
        assertNull(scene.focusedNodeOrNull(), size)

        // Focus did not drop to the root: the arrow keys
        // still move between the rows, and over the note.
        press(Key.DirectionDown)
        assertEquals(itemFieldTexts(PHONE), scene.focusedTexts(), size)
        press(Key.DirectionUp)
        assertNull(scene.focusedNodeOrNull(), size)
        press(Key.DirectionUp)
        assertEquals(itemFieldTexts(USERNAME), scene.focusedTexts(), size)
    }

    @Test
    fun aClickOnRevealFocusesNothing() = forEachVaultListSize(
        fields = { NOTE_FIELDS },
    ) { size ->
        clickSearchField()
        click(scene.nodeWithText(SECOND_ROW).boundsInRoot.center)
        val reveal = scene.nodeWithText(revealSecureNote()).boundsInRoot
        val focused = scene.focusedNodeOrNull()?.boundsInRoot

        click(reveal.center)
        assertTrue(hasText(NOTE_TEXT), size)
        assertEquals(InputMode.Touch, scene.inputMode, size)
        assertEquals(focused, scene.focusedNodeOrNull()?.boundsInRoot, size)
    }
}

private const val SECRET = "Secret"

private const val LINKED = "Linked"

private const val REFERENCE_FIELD = "Referenced field"

private const val CARDHOLDER = "Alice Cardholder"

private const val ROOT_FOLDER = "Root folder"

private const val PARENT_FOLDER = "Parent folder"

private const val FOLDER = "Folder"

private const val RP_ID = "example.com"

private const val ATTACHMENT = "attachment.txt"

private const val NOTE_TEXT = "The secure note"

/** The [itemFieldAction] of the [title], the row shows it as its Copy button. */
private fun copyAction(title: String) = FlatItemAction(
    title = TextHolder.Value(itemFieldAction(title)),
    type = FlatItemAction.Type.COPY,
)

/** A concealed field with the [dropdown] as its menu, between two fields. */
private fun secretFields(dropdown: List<FlatItemAction>) = listOf(
    itemField(USERNAME),
    VaultViewItem.Value(
        id = SECRET,
        title = SECRET,
        value = "hunter2",
        visibility = Visibility(concealed = true),
        dropdown = dropdown,
    ),
    itemField(PHONE),
)

/** A concealed field with a menu, so the row has an eye and Copy. */
private val SECRET_FIELDS = secretFields(dropdown = listOf(copyAction(SECRET)))

/** Rows with buttons inside of them, between two fields. */
private val ROWS_WITH_BUTTONS = listOf<VaultViewItem>(
    itemField(USERNAME),
    VaultViewItem.Value(
        id = LINKED,
        title = LINKED,
        value = "linked value",
        dropdown = listOf(copyAction(LINKED)),
        reference = VaultViewItem.Value.Reference(
            item = null,
            field = REFERENCE_FIELD,
            onClick = {},
        ),
    ),
    VaultViewItem.Card(
        id = "card",
        elevation = 0.dp,
        data = DSecret.Card(
            cardholderName = CARDHOLDER,
            number = "4111111111111111",
        ),
        visibility = Visibility(concealed = true),
        dropdown = persistentListOf(copyAction("card")),
    ),
    VaultViewItem.Folder(
        id = "folder",
        nodes = listOf(ROOT_FOLDER, PARENT_FOLDER, FOLDER).map { name ->
            VaultViewItem.Folder.FolderNode(name = name, onClick = {})
        },
        onClick = {},
    ),
    VaultViewItem.Passkey(
        id = "passkey",
        value = null,
        source = DSecret.Login.Fido2Credentials(
            credentialId = "credential",
            keyType = "public-key",
            keyAlgorithm = "ECDSA",
            keyCurve = "P-256",
            keyValue = "",
            rpId = RP_ID,
            rpName = null,
            counter = null,
            userHandle = null,
            userName = null,
            userDisplayName = null,
            discoverable = true,
            creationDate = Instant.fromEpochSeconds(0),
        ),
        onUse = {},
        onClick = {},
    ),
    VaultViewItem.Attachment(
        id = "attachment",
        item = AttachmentItem(
            key = "attachment",
            name = ATTACHMENT,
            extension = null,
            size = null,
            statusState = MutableStateFlow(AttachmentItem.Status.None),
            actionsState = MutableStateFlow(
                listOf(
                    FlatItemAction(title = TextHolder.Value("Open"), type = FlatItemAction.Type.VIEW),
                    FlatItemAction(title = TextHolder.Value("Download"), type = FlatItemAction.Type.DOWNLOAD),
                ),
            ),
            selectableState = MutableStateFlow(
                SelectableItemState(
                    selecting = false,
                    selected = false,
                    onClick = null,
                    onLongClick = null,
                ),
            ),
        ),
    ),
    itemField(PHONE),
)

/** A concealed field without a menu: the row is no focus target, its eye is. */
private val PLAIN_SECRET_FIELDS = secretFields(dropdown = emptyList())

/** A concealed note, it shows a reveal row. */
private val NOTE_FIELDS = listOf<VaultViewItem>(
    itemField(USERNAME),
    VaultViewItem.Note(
        id = "note",
        content = VaultViewItem.Note.Content.Text(NOTE_TEXT),
        visibility = Visibility(concealed = true),
    ),
    itemField(PHONE),
)

private fun revealSecureNote() = runBlocking { getString(Res.string.reveal_secure_note) }

/**
 * From the focused row, Right moves along the [count] buttons inside of
 * it and stops at the last one, and Left moves back to the row. After
 * each Right, [onButton] gets the index of the focused button. Returns
 * the bounds of the buttons.
 */
private fun NavigationComposeHarness.assertRowButtons(
    count: Int,
    message: String,
    onButton: (index: Int) -> Unit = {},
): List<Rect> {
    val row = scene.focusedNode().boundsInRoot
    val rowTexts = scene.focusedTexts()
    val buttons = List(count) { index ->
        press(Key.DirectionRight)
        onButton(index)
        scene.focusedNode().boundsInRoot
    }
    assertEquals(count, buttons.distinct().size, "$message: $buttons")
    assertTrue(buttons.all { row.containsRect(it) }, "$message: not in the row $row: $buttons")
    // Nothing after the last one.
    press(Key.DirectionRight)
    assertEquals(buttons.last(), scene.focusedNode().boundsInRoot, message)

    buttons.reversed().drop(1).forEach { button ->
        press(Key.DirectionLeft)
        assertEquals(button, scene.focusedNode().boundsInRoot, message)
    }
    press(Key.DirectionLeft)
    assertEquals(row, scene.focusedNode().boundsInRoot, message)
    assertEquals(rowTexts, scene.focusedTexts(), message)
    return buttons
}

private fun Rect.containsRect(other: Rect) =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
