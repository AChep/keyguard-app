package com.artemchep.keyguard.apple.account

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.invokeAction

/** One presentation's account producer and actions. Methods and callbacks are main-confined. */
class AccountDetailSession internal constructor(
    private val subscribe: (publish: (AccountDetailSnapshot, Map<String, () -> Unit>) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<AccountDetailSnapshot, Map<String, () -> Unit>>()

    fun observe(onChange: (AccountDetailSnapshot) -> Unit): KeyguardCancellable =
        session.observe(onChange, subscribe)

    fun invokeAction(id: String) = session.withActions { it.invokeAction(id) }

    fun close() = session.close()
}
