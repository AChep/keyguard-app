package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickSearchInteractionTest {
    @Test
    fun `shortcut action takes precedence over other key handling`() {
        val state = createQuickSearchState(
            actions = listOf(
                QuickSearchAction(
                    type = QuickSearchActionType.CopyPrimary,
                    title = "Copy username",
                    shortcut = quickSearchShortcut(QuickSearchActionType.CopyPrimary),
                ),
            ),
        )

        val action = quickSearchKeyEventAction(
            input = QuickSearchKeyInput(
                key = Key.C,
                type = KeyEventType.KeyDown,
                isCtrlPressed = true,
            ),
            state = state,
            platform = Platform.Desktop.Windows,
        )

        assertEquals(
            QuickSearchKeyEventAction.PerformShortcutAction(QuickSearchActionType.CopyPrimary),
            action,
        )
    }

    @Test
    fun `arrow keys and tab produce navigation actions`() {
        val state = createQuickSearchState()

        assertEquals(
            QuickSearchKeyEventAction.MoveSelection(1),
            quickSearchKeyEventAction(
                input = QuickSearchKeyInput(Key.DirectionDown, KeyEventType.KeyDown),
                state = state,
            ),
        )
        assertEquals(
            QuickSearchKeyEventAction.MoveSelection(-1),
            quickSearchKeyEventAction(
                input = QuickSearchKeyInput(Key.DirectionUp, KeyEventType.KeyDown),
                state = state,
            ),
        )
        assertEquals(
            QuickSearchKeyEventAction.MoveActionSelection(1),
            quickSearchKeyEventAction(
                input = QuickSearchKeyInput(Key.Tab, KeyEventType.KeyDown),
                state = state,
            ),
        )
        assertEquals(
            QuickSearchKeyEventAction.MoveActionSelection(-1),
            quickSearchKeyEventAction(
                input = QuickSearchKeyInput(
                    key = Key.Tab,
                    type = KeyEventType.KeyDown,
                    isShiftPressed = true,
                ),
                state = state,
            ),
        )
    }

    @Test
    fun `enter uses selected action when available`() {
        val state = createQuickSearchState(
            actions = listOf(
                QuickSearchAction(
                    type = QuickSearchActionType.CopyPrimary,
                    title = "Copy username",
                ),
                QuickSearchAction(
                    type = QuickSearchActionType.CopySecret,
                    title = "Copy password",
                ),
            ),
            selectedActionIndex = 1,
        )

        val action = quickSearchKeyEventAction(
            input = QuickSearchKeyInput(Key.Enter, KeyEventType.KeyDown),
            state = state,
        )

        assertEquals(
            QuickSearchKeyEventAction.PerformSelectedAction(QuickSearchActionType.CopySecret),
            action,
        )
    }

    @Test
    fun `enter falls back to default action when no action is selected`() {
        val state = createQuickSearchState(
            defaultAction = QuickSearchActionType.CopyPrimary,
        )

        val action = quickSearchKeyEventAction(
            input = QuickSearchKeyInput(Key.Enter, KeyEventType.KeyDown),
            state = state,
        )

        assertEquals(
            QuickSearchKeyEventAction.PerformDefaultAction,
            action,
        )
    }

    @Test
    fun `escape clears a non empty query`() {
        val state = createQuickSearchState(query = "bank")

        val action = quickSearchKeyEventAction(
            input = QuickSearchKeyInput(Key.Escape, KeyEventType.KeyDown),
            state = state,
        )

        assertEquals(
            QuickSearchKeyEventAction.ClearQuery,
            action,
        )
    }

    @Test
    fun `empty query and non key down events are ignored`() {
        val state = createQuickSearchState()

        assertNull(
            quickSearchKeyEventAction(
                input = QuickSearchKeyInput(Key.Escape, KeyEventType.KeyDown),
                state = state,
            ),
        )
        assertNull(
            quickSearchKeyEventAction(
                input = QuickSearchKeyInput(Key.DirectionDown, KeyEventType.KeyUp),
                state = state,
            ),
        )
    }

    @Test
    fun `shortcut matching adapts for macos and windows modifier semantics`() {
        val macInput = QuickSearchKeyInput(
            key = Key.Backspace,
            type = KeyEventType.KeyDown,
            isMetaPressed = true,
        )
        val windowsInput = QuickSearchKeyInput(
            key = Key.C,
            type = KeyEventType.KeyDown,
            isCtrlPressed = true,
        )

        assertTrue(
            macInput.matches(
                shortcut = com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut(
                    key = Key.Delete,
                    isCtrlPressed = true,
                ),
                platform = Platform.Desktop.MacOS,
            ),
        )
        assertTrue(
            windowsInput.matches(
                shortcut = quickSearchShortcut(QuickSearchActionType.CopyPrimary)!!,
                platform = Platform.Desktop.Windows,
            ),
        )
    }
}

private fun createQuickSearchState(
    query: String = "",
    actions: List<QuickSearchAction> = emptyList(),
    selectedActionIndex: Int? = null,
    defaultAction: QuickSearchActionType? = null,
) = QuickSearchState(
    query = TextFieldModel2(mutableStateOf(query)),
    actions = actions,
    selectedActionIndex = selectedActionIndex,
    defaultAction = defaultAction,
)
