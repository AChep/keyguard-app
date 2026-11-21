package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInputModeManager
import com.artemchep.keyguard.feature.navigation.keyboard.isPlainKeyDown
import kotlinx.coroutines.launch

/**
 * Entering the result list gives its first row normal arrow and activation key handling.
 * [firstResult] is on the first row, or on a focus group around the rows if [group]: a
 * group takes focus with Down, so it enters at its top row once the rows are placed.
 * A move switches to keyboard input mode.
 */
internal fun Modifier.focusFirstResultOnDown(
    state: LazyListState,
    firstResult: FocusRequester,
    firstIndex: Int?,
    group: Boolean = false,
): Modifier = this then FocusFirstResultOnDownElement(
    state = state,
    firstResult = firstResult,
    firstIndex = firstIndex,
    group = group,
)

private data class FocusFirstResultOnDownElement(
    val state: LazyListState,
    val firstResult: FocusRequester,
    val firstIndex: Int?,
    val group: Boolean,
) : ModifierNodeElement<FocusFirstResultOnDownNode>() {
    override fun create() = FocusFirstResultOnDownNode(
        state = state,
        firstResult = firstResult,
        firstIndex = firstIndex,
        group = group,
    )

    override fun update(node: FocusFirstResultOnDownNode) {
        node.state = state
        node.firstResult = firstResult
        node.firstIndex = firstIndex
        node.group = group
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusFirstResultOnDown"
        properties["firstIndex"] = firstIndex
        properties["group"] = group
    }
}

private class FocusFirstResultOnDownNode(
    var state: LazyListState,
    var firstResult: FocusRequester,
    var firstIndex: Int?,
    var group: Boolean,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    KeyInputModifierNode {
    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val index = firstIndex
        if (index == null || !event.isPlainKeyDown || event.key != Key.DirectionDown) {
            return false
        }
        focusFirstResult(index)
        // Consumed even if focus does not move.
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false

    private fun focusFirstResult(index: Int) {
        // The values at the key press. Only the
        // first index is read again after the scroll.
        val state = state
        val firstResult = firstResult
        val group = group
        val inputModeManager = currentValueOf(LocalInputModeManager)
        coroutineScope.launch {
            val scrolls = state.firstVisibleItemIndex != index ||
                state.firstVisibleItemScrollOffset != 0
            state.scrollToItem(index)
            // A group only enters a placed row. Waiting only
            // after a scroll keeps a quick Down, Enter working.
            if (group && scrolls) awaitScrolledItemsPlaced()
            if (firstIndex == index) {
                val direction = if (group) FocusDirection.Down else FocusDirection.Enter
                if (firstResult.requestFocus(direction)) {
                    // Like an arrow key move, also after a click on the field.
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                }
            }
        }
    }
}
