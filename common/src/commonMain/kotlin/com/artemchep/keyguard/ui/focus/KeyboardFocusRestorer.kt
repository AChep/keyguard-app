package com.artemchep.keyguard.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusPropertiesModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.invalidateFocusProperties
import androidx.compose.ui.focus.restoreFocusedChild
import androidx.compose.ui.focus.saveFocusedChild
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Remembers the child of the next focus group that had focus when focus
 * left the group. Unlike `focusRestorer`, entering the group moves no
 * focus. A restore of a group above goes on through it: a
 * [tabFocusRestorer], or `FocusRequester.restoreFocusedChild`.
 */
internal fun Modifier.saveFocusedChildOnExit(): Modifier = this then FocusMemoryElement(restoreOnTab = false)

/**
 * Like `focusRestorer`, but only for Tab and Shift+Tab. Entering the next
 * focus group with them focuses the child that had focus when focus left
 * the group. The restore goes on through the nested groups that
 * [saveFocusedChildOnExit].
 *
 * Only the Tab directions restore: a request from code, such as the
 * initial focus of a screen, enters with another direction and must get
 * what it asked for. Other keys that enter the group restore through
 * `FocusRequester.restoreFocusedChild`.
 */
internal fun Modifier.tabFocusRestorer(): Modifier = this then FocusMemoryElement(restoreOnTab = true)

/**
 * Focuses the child that had focus when focus left the group of this
 * requester, see [saveFocusedChildOnExit]. If there is none, requests
 * focus from the [direction].
 */
internal fun FocusRequester.restoreFocusedChildOrRequestFocus(direction: FocusDirection) {
    if (!restoreFocusedChild()) {
        requestFocus(direction)
    }
}

private data class FocusMemoryElement(
    val restoreOnTab: Boolean,
) : ModifierNodeElement<FocusMemoryNode>() {
    override fun create() = FocusMemoryNode(restoreOnTab)

    override fun update(node: FocusMemoryNode) {
        node.restoreOnTab = restoreOnTab
        node.invalidateFocusProperties()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = if (restoreOnTab) "tabFocusRestorer" else "saveFocusedChildOnExit"
    }
}

private class FocusMemoryNode(
    var restoreOnTab: Boolean,
) : Modifier.Node(),
    FocusPropertiesModifierNode,
    FocusRequesterModifierNode {
    // Focus has not moved yet, so the
    // child still holds it.
    private val onExit: FocusEnterExitScope.() -> Unit = {
        saveFocusedChild()
    }

    // A restore requests focus, which
    // redirects the focus change.
    private val onEnter: FocusEnterExitScope.() -> Unit = {
        if (
            requestedFocusDirection == FocusDirection.Next ||
            requestedFocusDirection == FocusDirection.Previous
        ) {
            restoreFocusedChild()
        }
    }

    override fun applyFocusProperties(focusProperties: FocusProperties) {
        focusProperties.onExit = onExit
        if (restoreOnTab) {
            focusProperties.onEnter = onEnter
        }
    }
}
