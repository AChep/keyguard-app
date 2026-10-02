package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot

/** One Recents sheet's producer, tab selection, and observation lifetime. */
class RecentsSession internal constructor(
    private val subscribe: (
        onListDelta: (VaultListDelta) -> Unit,
        publishTabs: (RecentsTabsSnapshot, ((String) -> Unit)?) -> Unit,
        publishTotp: (Map<String, TotpFieldSnapshot>) -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<RecentsTabsSnapshot, ((String) -> Unit)?>()

    /**
     * Starts once, on Main. Tabs and TOTP are delivered on Main; list deltas arrive off-main
     * for conversion by the shared Swift delta pump. Its generation rejects in-flight deltas on close.
     * Cancelling the returned observation closes all three channels and this session's producer.
     */
    fun observe(
        onListDelta: (VaultListDelta) -> Unit,
        onTabs: (RecentsTabsSnapshot) -> Unit,
        onTotp: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable = session.observe(onTabs) { publish ->
        subscribe(onListDelta, publish, session.gated(onTotp))
    }

    fun setTab(key: String) = session.withActions { it?.invoke(key) }

    fun close() = session.close()
}
