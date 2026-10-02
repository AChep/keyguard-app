package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One presentation's filtered history. Methods and callbacks are main-confined. */
class SshAgentHistorySession internal constructor(
    private val subscribe: (publish: (SshAgentHistorySnapshot) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<SshAgentHistorySnapshot, Unit>()

    fun observe(onChange: (SshAgentHistorySnapshot) -> Unit): KeyguardCancellable =
        session.observe(onChange) { publish -> subscribe { publish(it, Unit) } }

    fun close() = session.close()
}
