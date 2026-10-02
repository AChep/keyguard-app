package com.artemchep.keyguard.apple.send

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.ListSessionActions
import com.artemchep.keyguard.apple.model.invokeAction

/** One Send list's query, selection, and actions. Methods and callbacks are main-confined. */
class SendListSession internal constructor(
    private val subscribe: (publish: (SendListSnapshot, SendListSessionActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<SendListSnapshot, SendListSessionActions>()

    fun observe(onChange: (SendListSnapshot) -> Unit): KeyguardCancellable = session.observe(onChange, subscribe)

    fun setQuery(text: String) = session.withActions { it.query?.invoke(text) }

    fun invokeFilter(id: String) = session.withActions { it.filters[id]?.invoke() }

    fun invokeSort(id: String) = session.withActions { it.sort[id]?.invoke() }

    fun clearFilters() = session.withActions { it.clearFilters?.invoke() }

    fun clearSort() = session.withActions { it.clearSort?.invoke() }

    fun toggleSelection(itemId: String) = session.withActions { it.list.toggleSelection(itemId) }

    fun invokeSelectionAction(id: String) = session.withActions { it.list.selection.invokeAction(id) }

    fun invokeAction(id: String) = session.withActions { it.list.screen.invokeAction(id) }

    fun clearSelection() = session.withActions { it.list.clearSelection?.invoke() }

    fun dropFile(uri: String, name: String?, size: Long) = session.withActions { it.dropFile?.invoke(uri, name, size) }

    fun close() = session.close()
}

internal data class SendListSessionActions(
    val list: ListSessionActions = ListSessionActions(),
    val query: ((String) -> Unit)? = null,
    val filters: Map<String, () -> Unit> = emptyMap(),
    val sort: Map<String, () -> Unit> = emptyMap(),
    val clearFilters: (() -> Unit)? = null,
    val clearSort: (() -> Unit)? = null,
    val dropFile: ((String, String?, Long) -> Unit)? = null,
)
