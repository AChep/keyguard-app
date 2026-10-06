package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.apple.model.mapFilterItemsToSnapshots
import com.artemchep.keyguard.feature.home.vault.model.FilterItem

/** The key filter editor of the SSH or GPG agent; items reuse the vault filter projection. */
data class AgentFiltersSnapshot(
    val loaded: Boolean,
    val count: Int,
    val items: List<VaultFilterItemSnapshot>,
    val canSave: Boolean,
    val canReset: Boolean,
) {
    companion object {
        val empty = AgentFiltersSnapshot(false, 0, emptyList(), false, false)
    }
}

/** One agent filter editor's draft, actions, and completion. Methods and callbacks are main-confined. */
class AgentFiltersSession internal constructor(
    private val subscribe: (
        publish: (AgentFiltersSnapshot, AgentFilterActions) -> Unit,
        complete: () -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<AgentFiltersSnapshot, AgentFilterActions>()

    fun observe(onChange: (AgentFiltersSnapshot) -> Unit, onClose: () -> Unit): KeyguardCancellable =
        session.observe(onChange, onClose, subscribe)

    fun invokeFilter(id: String) = session.withActions { it.filters[id]?.invoke() }

    fun save() = session.withActions { it.save?.invoke() }

    fun reset() = session.withActions { it.reset?.invoke() }

    fun close() = session.close()
}

internal data class AgentFilterActions(
    val filters: Map<String, () -> Unit> = emptyMap(),
    val save: (() -> Unit)? = null,
    val reset: (() -> Unit)? = null,
)

/** Projects one agent filter state, or the empty editor while it loads or the vault is locked. */
internal fun agentFiltersFrame(
    filters: List<FilterItem>?,
    count: Int?,
    onSave: (() -> Unit)?,
    onReset: (() -> Unit)?,
): Pair<AgentFiltersSnapshot, AgentFilterActions> {
    filters ?: return AgentFiltersSnapshot.empty to AgentFilterActions()
    val handlers = LinkedHashMap<String, () -> Unit>()
    val snapshot = AgentFiltersSnapshot(
        loaded = true,
        count = count ?: 0,
        items = mapFilterItemsToSnapshots(filters, handlers),
        canSave = onSave != null,
        canReset = onReset != null,
    )
    return snapshot to AgentFilterActions(filters = handlers, save = onSave, reset = onReset)
}
