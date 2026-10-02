package com.artemchep.keyguard.apple.watchtower

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.invokeAction

/** A dashboard's filters and actions, independent of shared Watchtower preferences. Main-confined. */
class WatchtowerSession internal constructor(
    private val subscribe: (publish: (WatchtowerSnapshot, WatchtowerSessionActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<WatchtowerSnapshot, WatchtowerSessionActions>()

    fun observe(onChange: (WatchtowerSnapshot) -> Unit): KeyguardCancellable = session.observe(onChange, subscribe)
    fun invokeWatchtowerAction(id: String) = session.withActions { it.actions.invokeAction(id) }
    fun invokeWatchtowerFilter(id: String) = session.withActions { it.filters[id]?.invoke() }
    fun clearWatchtowerFilters() = session.withActions { it.clearFilters?.invoke() }
    fun close() = session.close()
}

internal data class WatchtowerSessionActions(
    val actions: Map<String, () -> Unit> = emptyMap(),
    val filters: Map<String, () -> Unit> = emptyMap(),
    val clearFilters: (() -> Unit)? = null,
)
