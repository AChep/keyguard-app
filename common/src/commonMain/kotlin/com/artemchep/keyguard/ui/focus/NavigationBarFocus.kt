package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusPropertiesModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * The focus group of a navigation rail or bar with the given [orientation].
 * The arrow keys along it move focus between its items and stop at its
 * ends. The key toward the content calls [onFocusContent], the key away
 * from it does nothing. A key that moves focus switches to keyboard
 * input mode. The [focusedState] is `true` while the group holds focus.
 */
internal fun Modifier.navigationBarFocusGroup(
    orientation: Orientation,
    focusedState: MutableState<Boolean>,
    onFocusContent: () -> Unit,
): Modifier = this
    .then(
        NavigationBarFocusElement(
            orientation = orientation,
            focusedState = focusedState,
            onFocusContent = onFocusContent,
        ),
    )
    .focusGroup()

private data class NavigationBarFocusElement(
    val orientation: Orientation,
    val focusedState: MutableState<Boolean>,
    val onFocusContent: () -> Unit,
) : ModifierNodeElement<NavigationBarFocusNode>() {
    override fun create() = NavigationBarFocusNode(
        orientation = orientation,
        focusedState = focusedState,
        onFocusContent = onFocusContent,
    )

    override fun update(node: NavigationBarFocusNode) {
        node.orientation = orientation
        node.onFocusContent = onFocusContent
        node.focusedState = focusedState
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "navigationBarFocusGroup"
        properties["orientation"] = orientation
    }
}

private class NavigationBarFocusNode(
    var orientation: Orientation,
    var focusedState: MutableState<Boolean>,
    var onFocusContent: () -> Unit,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    FocusEventModifierNode,
    FocusPropertiesModifierNode,
    KeyInputModifierNode {
    // Main thread only: the key handler sets
    // it, the focus callbacks read it.
    private var moving = false

    // A move along the bar stops at its ends.
    private val onExit: FocusEnterExitScope.() -> Unit = {
        if (moving) cancelFocusChange()
    }

    override fun applyFocusProperties(focusProperties: FocusProperties) {
        focusProperties.onExit = onExit
    }

    override fun onFocusEvent(focusState: FocusState) {
        focusedState.value = focusState.hasFocus
    }

    override fun onDetach() {
        // The bar may leave while it holds focus.
        focusedState.value = false
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val direction = event.arrowKeyFocusDirection()
            ?: return false
        val vertical = orientation == Orientation.Vertical
        when (direction) {
            contentDirection(vertical) -> {
                // Before the request, so the content sees
                // the input mode the focus came with.
                currentValueOf(LocalInputModeManager).requestInputMode(InputMode.Keyboard)
                onFocusContent()
            }

            FocusDirection.Up,
            FocusDirection.Down,
            -> if (vertical) moveAlong(direction)

            FocusDirection.Left,
            FocusDirection.Right,
            -> if (!vertical) moveAlong(direction)
        }
        // Consumed even if focus did not move,
        // so nothing else handles the key.
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false

    /** The content is on the end side of a rail, and above a bar. */
    private fun contentDirection(vertical: Boolean): FocusDirection = when {
        !vertical -> FocusDirection.Up
        currentValueOf(LocalLayoutDirection) == LayoutDirection.Ltr -> FocusDirection.Right
        else -> FocusDirection.Left
    }

    private fun moveAlong(direction: FocusDirection) {
        moving = true
        try {
            moveFocusFromKeyboard(direction)
        } finally {
            moving = false
        }
    }
}
