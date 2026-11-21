package com.artemchep.keyguard.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Requests focus once, the first time [enabled] is true. Later flips of
 * [enabled] (for example while the dialog is busy) do not take focus back.
 */
fun Modifier.focusOnDialogOpen(enabled: Boolean = true): Modifier = this then FocusOnDialogOpenElement(enabled)

private data class FocusOnDialogOpenElement(
    val enabled: Boolean,
) : ModifierNodeElement<FocusOnDialogOpenNode>() {
    override fun create() = FocusOnDialogOpenNode(enabled)

    override fun update(node: FocusOnDialogOpenNode) {
        node.update(enabled)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusOnDialogOpen"
        properties["enabled"] = enabled
    }
}

private class FocusOnDialogOpenNode(
    private var enabled: Boolean,
) : Modifier.Node(),
    FocusRequesterModifierNode {
    // Kept across updates. A reused lazy
    // item requests focus again.
    private var requested = false

    private var job: Job? = null

    override fun onAttach() {
        scheduleRequest()
    }

    override fun onReset() {
        requested = false
    }

    fun update(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        // A detached node schedules on attach.
        if (isAttached) scheduleRequest()
    }

    /**
     * Like a `LaunchedEffect` keyed on [enabled], the request starts
     * after the changes to the tree are applied. While they are, the
     * focus target below cannot take focus yet.
     */
    private fun scheduleRequest() {
        job?.cancel()
        job = null
        sideEffect(launchRequest)
    }

    // A single instance, so the owner runs
    // it at most once per applied change.
    private val launchRequest: () -> Unit = {
        if (isAttached && enabled && !requested) {
            job = coroutineScope.launch {
                requested = true
                requestFocus()
            }
        }
    }
}
