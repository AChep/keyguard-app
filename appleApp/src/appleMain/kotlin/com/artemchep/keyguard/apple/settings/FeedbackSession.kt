package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One feedback form's message and actions. Methods and callbacks are main-confined. */
class FeedbackSession internal constructor(
    private val subscribe: (publish: (FeedbackSnapshot, FeedbackActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<FeedbackSnapshot, FeedbackActions>()

    fun observe(onChange: (FeedbackSnapshot) -> Unit): KeyguardCancellable =
        session.observe(onChange, subscribe)

    fun setMessage(text: String) = session.withActions { it.setMessage?.invoke(text) }

    fun submit() = session.withActions { it.submit?.invoke() }

    fun close() = session.close()
}

internal data class FeedbackActions(
    val setMessage: ((String) -> Unit)? = null,
    val submit: (() -> Unit)? = null,
)
