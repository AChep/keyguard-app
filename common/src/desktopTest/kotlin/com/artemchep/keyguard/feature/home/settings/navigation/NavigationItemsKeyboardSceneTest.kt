@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keyboard paths through the Navigation items settings page. */
class NavigationItemsKeyboardSceneTest {
    // Internal, so the scene helpers below the class can reach it.
    internal val entryScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        entryScope.cancel()
    }

    @Test
    fun pageFocusesItsFirstRowAndDownMovesToTheNextRow() {
        withScreen(NavigationItemsStore()) {
            assertEquals(listOf(row(0)), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf(row(1)), focusedTexts())
        }
    }

    @Test
    fun visibilityButtonIsNoTabStopButAClickTogglesIt() {
        val store = NavigationItemsStore()
        withScreen(store) {
            press(Key.Tab)
            assertOptionsFocused()
            press(Key.Tab)
            assertEquals(listOf(row(1)), focusedTexts())

            mouseClick(visibilityButtonOf(row(0)).boundsInRoot.center)
            assertEquals(1, store.toggles)
        }
    }

    @Test
    fun rightFocusesTheOptionsButtonAndLeftReturnsToTheRow() {
        withScreen(NavigationItemsStore()) {
            press(Key.DirectionRight)
            assertOptionsFocused()
            press(Key.DirectionLeft)
            assertEquals(listOf(row(0)), focusedTexts())
        }
    }

    @Test
    fun altArrowsMoveTheFocusedRowAndKeepItInView() {
        val store = NavigationItemsStore()
        withScreen(store) {
            // The list is not scrolled yet.
            val viewTop = focusedNode().boundsInRoot.top
            val viewBottom = listNode().boundsInRoot.bottom

            press(Key.DirectionDown, alt = true)
            advanceTimeBy(1_000L)
            // The list keeps its position: the row
            // below takes the place of the moved one.
            assertEquals(viewTop, nodeWithText(row(1)).boundsInRoot.top)
            assertMovedRowFocused(store, index = 1, viewTop, viewBottom)

            // The last rows start below the view, so the list scrolls.
            for (index in 2 until ROW_COUNT) {
                press(Key.DirectionDown, alt = true)
                advanceTimeBy(1_000L)
                assertMovedRowFocused(store, index, viewTop, viewBottom)
            }

            // The last row can not move down, but the key is still taken.
            val reorders = store.reorders.size
            assertTrue(sendAltKeyDown(Key.DirectionDown))
            draw()
            assertEquals(reorders, store.reorders.size)

            for (index in ROW_COUNT - 2 downTo 0) {
                press(Key.DirectionUp, alt = true)
                advanceTimeBy(1_000L)
                assertMovedRowFocused(store, index, viewTop, viewBottom)
            }
        }
    }

    @Test
    fun altArrowsDoNothingWhileARowIsDragged() {
        val store = NavigationItemsStore()
        withScreen(store) {
            val options = optionsButtonOf(row(0)).boundsInRoot
            // The drag handle comes right after the options button.
            val handle = Offset(options.right + DRAG_HANDLE_SIZE / 2, options.center.y)
            sendPointerEvent(
                eventType = PointerEventType.Press,
                position = handle,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            // Past the slop, but still over the row.
            sendPointerEvent(
                eventType = PointerEventType.Move,
                position = handle + Offset(0f, DRAG_DISTANCE),
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            draw()
            assertEquals(listOf(row(0)), focusedTexts())

            press(Key.DirectionDown, alt = true)
            assertEquals(emptyList<List<String>>(), store.reorders)

            sendPointerEvent(
                eventType = PointerEventType.Release,
                position = handle + Offset(0f, DRAG_DISTANCE),
                buttons = PointerButtons(),
                button = PointerButton.Primary,
            )
            draw()
            // The drag commits the order it left.
            assertEquals(listOf(rows()), store.reorders)
        }
    }

    @Test
    fun upFromTheAddButtonReturnsToTheRowFocusedLast() {
        withScreen(NavigationItemsStore(canAdd = true)) {
            // The lowest button that is not in a row.
            val add = mergedNodes()
                .filter { node ->
                    node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                        node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
                }
                .maxBy { node -> node.boundsInRoot.bottom }
                .id
            val lastRow = row(ROW_COUNT - 1)
            // The list scrolls to each row.
            repeat(ROW_COUNT - 1) {
                press(Key.DirectionDown)
                advanceTimeBy(1_000L)
            }
            assertEquals(listOf(lastRow), focusedTexts())
            // The button covers the last row, so Down finds nothing.
            // Tab goes by the row's Options button first.
            repeat(2) { press(Key.Tab) }
            assertEquals(add, focusedNode().id)

            // The 2D search would skip the list under the button.
            listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight).forEach { key ->
                press(key)
                assertEquals(add, focusedNode().id, "$key")
            }
            press(Key.DirectionUp)
            assertEquals(listOf(lastRow), focusedTexts())
        }
    }

    @Test
    fun pageFocusesNothingInTouchMode() {
        var shown by mutableStateOf(false)
        withScreen(NavigationItemsStore(), shown = { shown }) {
            mouseClick(Offset(450f, 600f))
            assertEquals(InputMode.Touch, inputMode)

            shown = true
            advanceTimeBy(1_000L)
            assertNull(focusedOrNull())
        }
    }
}

private const val ROW_COUNT = 14

private const val OPTIONS = "Options"

private const val VISIBLE = "Visible"

private const val AVAILABLE = "Hidden"

private const val DRAG_HANDLE_SIZE = 36f

private const val DRAG_DISTANCE = 12f

private fun row(index: Int) = "Row $index"

private fun rows() = List(ROW_COUNT, ::row)

/**
 * The page's items, kept in order the way its state producer does.
 * With [canAdd], the page has an item to add, so it shows the add button.
 */
private class NavigationItemsStore(
    private val canAdd: Boolean = false,
) {
    var order by mutableStateOf(rows())

    val reorders = mutableListOf<List<String>>()

    var toggles = 0

    fun state(): NavigationItemsSettingsState {
        val order = order
        return NavigationItemsSettingsState(
            items = order
                .mapIndexed { index, title ->
                    NavigationItemsSettingsState.Item(
                        key = title,
                        ref = NavItemRef.BuiltIn(title),
                        title = TextHolder.Value(title),
                        icon = Icons.Outlined.Star,
                        subIcon = null,
                        visible = true,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        canRemove = false,
                        onVisibilityToggle = {
                            toggles++
                        },
                        onMoveUp = null,
                        onMoveDown = null,
                        onRemove = null,
                    )
                }
                .toPersistentList(),
            availableItems = if (canAdd) {
                persistentListOf(
                    NavigationItemsSettingsState.AvailableItem(
                        key = AVAILABLE,
                        ref = NavItemRef.BuiltIn(AVAILABLE),
                        title = TextHolder.Value(AVAILABLE),
                        text = null,
                        icon = Icons.Outlined.Star,
                        onAdd = {},
                    ),
                )
            } else {
                persistentListOf()
            },
            onReorder = { refs ->
                val titles = refs.map { (it as NavItemRef.BuiltIn).key }
                reorders += titles
                this.order = titles
            },
            onReset = {},
        )
    }
}

private fun ImageComposeScene.assertOptionsFocused() {
    assertEquals(listOf(OPTIONS), focused()[SemanticsProperties.ContentDescription])
}

/** The row first moved to [index] keeps focus and stays between [viewTop] and [viewBottom]. */
private fun ImageComposeScene.assertMovedRowFocused(
    store: NavigationItemsStore,
    index: Int,
    viewTop: Float,
    viewBottom: Float,
) {
    assertEquals(index, store.order.indexOf(row(0)))
    assertEquals(store.order, store.reorders.last())
    assertEquals(listOf(row(0)), focusedTexts())
    val bounds = focusedNode().boundsInRoot
    assertTrue(bounds.top >= viewTop && bounds.bottom <= viewBottom, "$bounds is out of the view")
}

private fun ImageComposeScene.sendAltKeyDown(key: Key): Boolean {
    val consumed = sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isAltPressed = true))
    sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isAltPressed = true))
    return consumed
}

private fun ImageComposeScene.listNode(): SemanticsNode =
    mergedNodes().first { SemanticsProperties.CollectionInfo in it.config }

private fun ImageComposeScene.visibilityButtonOf(title: String): SemanticsNode =
    buttonOf(title, VISIBLE)

private fun ImageComposeScene.optionsButtonOf(title: String): SemanticsNode =
    buttonOf(title, OPTIONS)

/** The button with the [description] inside the row with the [title]. */
private fun ImageComposeScene.buttonOf(title: String, description: String): SemanticsNode {
    val row = nodeWithText(title).boundsInRoot
    return mergedNodes().first { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(description) &&
            row.containsVertically(node.boundsInRoot.center)
    }
}

private fun Rect.containsVertically(offset: Offset) = offset.y in top..bottom

/** The page under a navigation entry, the way its route shows it. */
private fun NavigationItemsKeyboardSceneTest.withScreen(
    store: NavigationItemsStore,
    shown: () -> Boolean = { true },
    test: ImageComposeScene.() -> Unit,
) {
    val entry = NavigationEntryImpl(
        source = "test",
        parent = entryScope,
        id = "navigation_items",
        route = NavigationItemsSettingsRoute,
    )
    withScene({
        CompositionLocalProvider(
            LocalNavigationEntry provides entry,
        ) {
            if (shown()) {
                val state = remember(store.order) {
                    store.state()
                }
                NavigationItemsSettingsContent(state)
            }
        }
    }, test = test)
}
