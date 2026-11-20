package com.artemchep.keyguard.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import com.artemchep.keyguard.feature.navigation.keyboard.isPlainKeyDown

/**
 * Moves focus with the arrow keys. Desktop scroll containers consume arrow
 * keys unless their choices move focus first. Focus moves by position, so
 * Left and Right can cross panes. A move switches to keyboard input mode.
 *
 * With [afterChildren], the focused child handles the keys first, so a
 * text field keeps moving its caret. Put it inside any scroll container.
 */
internal fun Modifier.arrowKeyFocusNavigation(
    afterChildren: Boolean = false,
): Modifier = this then ArrowKeyFocusNavigationElement(afterChildren)

/** The direction of a plain arrow key press, or `null` for other events. */
internal fun KeyEvent.arrowKeyFocusDirection(): FocusDirection? {
    if (!isPlainKeyDown) return null
    return when (key) {
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
}

/**
 * Moves focus in the [direction], and switches to keyboard input mode
 * if it moved. Returns `true` if it moved.
 */
internal fun CompositionLocalConsumerModifierNode.moveFocusFromKeyboard(
    direction: FocusDirection,
): Boolean {
    val moved = currentValueOf(LocalFocusManager).moveFocus(direction)
    if (moved) {
        currentValueOf(LocalInputModeManager).requestInputMode(InputMode.Keyboard)
    }
    return moved
}

private data class ArrowKeyFocusNavigationElement(
    val afterChildren: Boolean,
) : ModifierNodeElement<ArrowKeyFocusNavigationNode>() {
    override fun create() = ArrowKeyFocusNavigationNode(afterChildren)

    override fun update(node: ArrowKeyFocusNavigationNode) {
        node.afterChildren = afterChildren
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "arrowKeyFocusNavigation"
        properties["afterChildren"] = afterChildren
    }
}

private class ArrowKeyFocusNavigationNode(
    var afterChildren: Boolean,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    KeyInputModifierNode {
    override fun onPreKeyEvent(event: KeyEvent): Boolean =
        !afterChildren && moveFocus(event)

    override fun onKeyEvent(event: KeyEvent): Boolean =
        afterChildren && moveFocus(event)

    private fun moveFocus(event: KeyEvent): Boolean {
        val direction = event.arrowKeyFocusDirection()
            ?: return false
        return moveFocusFromKeyboard(direction)
    }
}
