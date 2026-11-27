package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.auth.MasterKdfActions
import com.artemchep.keyguard.apple.auth.MasterKdfHandlers
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One key derivation editor's fields and completion. Methods and callbacks are main-confined. */
class ChangeKdfSession internal constructor(
    private val subscribe: (
        publish: (ChangeKdfSnapshot, ChangeKdfActions) -> Unit,
        complete: () -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<ChangeKdfSnapshot, ChangeKdfActions>()

    fun observe(onChange: (ChangeKdfSnapshot) -> Unit, onClose: () -> Unit): KeyguardCancellable =
        session.observe(onChange, onClose, subscribe)

    fun setPassword(text: String) = session.withActions { it.setPassword?.invoke(text) }

    val kdf: MasterKdfActions = MasterKdfActions { block -> session.withActions { block(it.kdf) } }

    fun setBiometric(enabled: Boolean) = session.withActions { it.setBiometric?.invoke(enabled) }

    fun submit() = session.withActions { it.submit?.invoke() }

    fun close() = session.close()
}

internal data class ChangeKdfActions(
    val setPassword: ((String) -> Unit)? = null,
    val kdf: MasterKdfHandlers = MasterKdfHandlers(),
    val setBiometric: ((Boolean) -> Unit)? = null,
    val submit: (() -> Unit)? = null,
)
