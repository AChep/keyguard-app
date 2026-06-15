package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

internal data class QuickSearchKeyInput(
    val key: Key,
    val type: KeyEventType,
    val isAltPressed: Boolean = false,
    val isCtrlPressed: Boolean = false,
    val isMetaPressed: Boolean = false,
    val isShiftPressed: Boolean = false,
)

internal sealed interface QuickSearchKeyEventAction {
    data class MoveSelection(
        val direction: Int,
    ) : QuickSearchKeyEventAction

    data class MoveActionSelection(
        val direction: Int,
    ) : QuickSearchKeyEventAction

    data class PerformSelectedAction(
        val type: QuickSearchActionType,
    ) : QuickSearchKeyEventAction

    data class PerformShortcutAction(
        val type: QuickSearchActionType,
    ) : QuickSearchKeyEventAction

    data object PerformDefaultAction : QuickSearchKeyEventAction

    data object ClearQuery : QuickSearchKeyEventAction
}

internal fun KeyEvent.toQuickSearchKeyInput() = QuickSearchKeyInput(
    key = key,
    type = type,
    isAltPressed = isAltPressed,
    isCtrlPressed = isCtrlPressed,
    isMetaPressed = isMetaPressed,
    isShiftPressed = isShiftPressed,
)

internal fun quickSearchKeyEventAction(
    input: QuickSearchKeyInput,
    state: QuickSearchState,
    platform: Platform = CurrentPlatform,
): QuickSearchKeyEventAction? {
    if (input.type != KeyEventType.KeyDown) {
        return null
    }

    val selectedAction = state.selectedActionIndex
        ?.let(state.actions::getOrNull)
    val shortcutAction = state.actions
        .firstOrNull { action ->
            val shortcut = action.shortcut
                ?: return@firstOrNull false
            input.matches(
                shortcut = shortcut,
                platform = platform,
            )
        }
    if (shortcutAction != null) {
        return QuickSearchKeyEventAction.PerformShortcutAction(shortcutAction.type)
    }

    return when (input.key) {
        Key.DirectionDown -> QuickSearchKeyEventAction.MoveSelection(1)
        Key.DirectionUp -> QuickSearchKeyEventAction.MoveSelection(-1)
        Key.Tab -> QuickSearchKeyEventAction.MoveActionSelection(
            direction = if (input.isShiftPressed) -1 else 1,
        )

        Key.Enter,
        Key.NumPadEnter,
            -> selectedAction
            ?.let { QuickSearchKeyEventAction.PerformSelectedAction(it.type) }
            ?: QuickSearchKeyEventAction.PerformDefaultAction

        Key.Escape -> if (state.query.text.isNotEmpty()) {
            QuickSearchKeyEventAction.ClearQuery
        } else {
            null
        }

        else -> null
    }
}

internal fun QuickSearchKeyInput.matches(
    shortcut: KeyShortcut,
    platform: Platform = CurrentPlatform,
): Boolean {
    val normalizedKey = when (platform) {
        is Platform.Desktop.MacOS,
        is Platform.Mobile,
            -> when (key) {
            Key.Backspace -> Key.Delete
            else -> key
        }

        else -> key
    }
    val isShortcutCtrlPressed = when (platform) {
        is Platform.Desktop.MacOS -> isMetaPressed
        else -> isCtrlPressed
    }
    return normalizedKey == shortcut.key &&
            isShortcutCtrlPressed == shortcut.isCtrlPressed &&
            isShiftPressed == shortcut.isShiftPressed &&
            isAltPressed == shortcut.isAltPressed
}
