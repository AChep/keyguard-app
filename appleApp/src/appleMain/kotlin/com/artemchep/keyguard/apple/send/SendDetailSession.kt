package com.artemchep.keyguard.apple.send

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.invokeAction

/** One presentation's Send producer and actions. Methods and callbacks are main-confined. */
class SendDetailSession internal constructor(
    private val subscribe: (publish: (SendDetailSnapshot, SendDetailActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<SendDetailSnapshot, SendDetailActions>()

    fun observe(onChange: (SendDetailSnapshot) -> Unit): KeyguardCancellable =
        session.observe(onChange, subscribe)

    fun invokeAction(id: String) = session.withActions { it.items.invokeAction(id) }

    fun sendCopy() = session.withActions { it.copy?.invoke() }

    fun sendShare() = session.withActions { it.share?.invoke() }

    fun sendEdit() = session.withActions { it.edit?.invoke() }

    fun close() = session.close()
}

internal data class SendDetailActions(
    val items: Map<String, () -> Unit> = emptyMap(),
    val copy: (() -> Unit)? = null,
    val share: (() -> Unit)? = null,
    val edit: (() -> Unit)? = null,
)
