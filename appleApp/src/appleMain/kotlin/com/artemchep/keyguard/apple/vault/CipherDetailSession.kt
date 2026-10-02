package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.invokeAction

/** One presentation's detail producer and actions. All methods and callbacks are main-confined. */
class CipherDetailSession internal constructor(
    private val subscribe: (
        publish: (VaultDetailSnapshot, Map<String, () -> Unit>, (() -> Unit)?) -> Unit,
        publishTotp: (VaultDetailTotpSnapshot?) -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<VaultDetailSnapshot, Actions>()

    /** Starts this session once. Cancelling the returned observation also closes the session. */
    fun observe(
        onChange: (VaultDetailSnapshot) -> Unit,
        onTotpChange: (VaultDetailTotpSnapshot?) -> Unit,
    ): KeyguardCancellable {
        return session.observe(onChange) { publish ->
            subscribe(
                { snapshot, handlers, favourite -> publish(snapshot, Actions(handlers, favourite)) },
                session.gated(onTotpChange),
            )
        }
    }

    fun invokeAction(id: String) = session.withActions { it.items.invokeAction(id) }

    fun toggleFavorite() = session.withActions { it.favourite?.invoke() }

    fun close() = session.close()

    private data class Actions(
        val items: Map<String, () -> Unit>,
        val favourite: (() -> Unit)?,
    )
}
