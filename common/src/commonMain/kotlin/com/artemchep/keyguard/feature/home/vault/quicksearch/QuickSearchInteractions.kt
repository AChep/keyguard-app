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

    data object OpenAutotypeMenu : QuickSearchKeyEventAction
}

internal val quickSearchAutotypeMenuShortcut = KeyShortcut(
    key = Key.T,
    isCtrlPressed = true,
    isShiftPressed = true,
)

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
    val shortcutAction = quickSearchShortcutAction(input, state.actions, platform)
    if (shortcutAction != null) {
        return shortcutAction
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

private fun quickSearchShortcutAction(
    input: QuickSearchKeyInput,
    actions: List<QuickSearchAction>,
    platform: Platform,
): QuickSearchKeyEventAction? = when {
    input.matches(quickSearchAutotypeMenuShortcut, platform) &&
            actions.any { it.type == QuickSearchActionType.Autotype } ->
        QuickSearchKeyEventAction.OpenAutotypeMenu

    else -> actions.firstOrNull { action ->
        action.shortcut?.let { input.matches(it, platform) } == true
    }?.let { QuickSearchKeyEventAction.PerformShortcutAction(it.type) }
}

internal sealed interface QuickSearchAutotypeMenuKeyAction {
    data object Dismiss : QuickSearchAutotypeMenuKeyAction
    data class Select(val field: QuickSearchAutotypeField) : QuickSearchAutotypeMenuKeyAction
    data class Perform(val field: QuickSearchAutotypeField) : QuickSearchAutotypeMenuKeyAction
}

internal fun quickSearchAutotypeMenuKeyAction(
    input: QuickSearchKeyInput,
    availableFields: List<QuickSearchAutotypeField>,
    selectedField: QuickSearchAutotypeField,
): QuickSearchAutotypeMenuKeyAction? = when {
    input.type != KeyEventType.KeyDown -> null
    input.key == Key.Escape -> QuickSearchAutotypeMenuKeyAction.Dismiss
    input.hasShortcutModifier || input.isShiftPressed -> null
    input.key == Key.DirectionDown -> moveAutotypeFieldSelection(availableFields, selectedField, 1)
    input.key == Key.DirectionUp -> moveAutotypeFieldSelection(availableFields, selectedField, -1)
    else -> autotypeFieldForKey(input.key, selectedField)
        ?.takeIf { it in availableFields }
        ?.let(QuickSearchAutotypeMenuKeyAction::Perform)
}

private val QuickSearchKeyInput.hasShortcutModifier: Boolean
    get() = isAltPressed || isCtrlPressed || isMetaPressed

private fun autotypeFieldForKey(
    key: Key,
    selectedField: QuickSearchAutotypeField,
): QuickSearchAutotypeField? = when (key) {
    Key.One, Key.NumPad1 -> QuickSearchAutotypeField.Username
    Key.Two, Key.NumPad2 -> QuickSearchAutotypeField.Password
    Key.Three, Key.NumPad3 -> QuickSearchAutotypeField.OneTimeCode
    Key.Enter, Key.NumPadEnter -> selectedField
    else -> null
}

private fun moveAutotypeFieldSelection(
    availableFields: List<QuickSearchAutotypeField>,
    selectedField: QuickSearchAutotypeField,
    direction: Int,
): QuickSearchAutotypeMenuKeyAction? {
    if (availableFields.isEmpty()) return null
    val index = availableFields.indexOf(selectedField).coerceAtLeast(0)
    val next = (index + direction + availableFields.size) % availableFields.size
    return QuickSearchAutotypeMenuKeyAction.Select(availableFields[next])
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
