package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.feature.attachments.SelectableItemState

/** One presentation of a selectable list. Methods and callbacks are main-confined. */
class ListSession<Snapshot : Any> internal constructor(
    private val subscribe: (publish: (Snapshot, ListSessionActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<Snapshot, ListSessionActions>()

    fun observe(onChange: (Snapshot) -> Unit): KeyguardCancellable = session.observe(onChange, subscribe)

    fun invokeItemAction(id: String) = session.withActions { it.items.invokeAction(id) }

    fun invokeSelectionAction(id: String) = session.withActions { it.selection.invokeAction(id) }

    fun invokeAction(id: String) = session.withActions { it.screen.invokeAction(id) }

    fun invokePrimaryAction() = session.withActions { it.primary?.invoke() }

    fun toggleSelection(itemId: String) = session.withActions { it.toggleSelection(itemId) }

    fun clearSelection() = session.withActions { it.clearSelection?.invoke() }

    fun close() = session.close()
}

/** Callbacks published atomically with one selectable list's immutable snapshot. */
internal data class ListSessionActions(
    val items: Map<String, () -> Unit> = emptyMap(),
    val selection: Map<String, () -> Unit> = emptyMap(),
    val screen: Map<String, () -> Unit> = emptyMap(),
    /** Resolves the row's live selection handle only when invoked; rapid toggles may precede the next frame. */
    val toggleSelection: (itemId: String) -> Unit = {},
    val clearSelection: (() -> Unit)? = null,
    val primary: (() -> Unit)? = null,
)

/** Toggles a selectable row: a click while selecting, otherwise the long click that starts selection. */
internal fun SelectableItemState.toggle() {
    (onClick ?: onLongClick)?.invoke()
}
