package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One presentation's filtered request log. Methods and callbacks are main-confined. */
class CliAgentHistorySession internal constructor(
    private val subscribe: (publish: (CliAgentHistorySnapshot) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<CliAgentHistorySnapshot, Unit>()

    fun observe(onChange: (CliAgentHistorySnapshot) -> Unit): KeyguardCancellable =
        session.observe(onChange) { publish -> subscribe { publish(it, Unit) } }

    fun close() = session.close()
}
