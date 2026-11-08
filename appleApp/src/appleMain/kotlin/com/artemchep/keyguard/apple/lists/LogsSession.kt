package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One presentation of the in-memory logs. Methods and callbacks are main-confined. */
class LogsSession internal constructor(
    private val subscribe: (publish: (LogsSnapshot, LogsSessionActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<LogsSnapshot, LogsSessionActions>()

    fun observe(onChange: (LogsSnapshot) -> Unit): KeyguardCancellable = session.observe(onChange, subscribe)

    /** Starts or stops recording; see [LogsSnapshot.recording]. */
    fun toggleRecording() = session.withActions { it.toggleRecording?.invoke() }

    /** Saves the recorded logs to a file. */
    fun export() = session.withActions { it.export?.invoke() }

    fun close() = session.close()
}

/** Callbacks published atomically with one logs snapshot. */
internal data class LogsSessionActions(
    val toggleRecording: (() -> Unit)? = null,
    val export: (() -> Unit)? = null,
)
