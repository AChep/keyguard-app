package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One unlock or setup form. Methods and callbacks are main-confined. */
class MasterPasswordSession internal constructor(
    private val subscribe: (publish: (MasterPasswordSnapshot, MasterPasswordActions) -> Unit) -> KeyguardCancellable,
) {
    private val session = DetailSession<MasterPasswordSnapshot, MasterPasswordActions>()

    fun observe(onChange: (MasterPasswordSnapshot) -> Unit): KeyguardCancellable = session.observe(onChange, subscribe)

    fun setPassword(text: String) = session.withActions { it.setPassword?.invoke(text) }

    fun setBiometric(enabled: Boolean) = session.withActions { it.setBiometric?.invoke(enabled) }

    fun setCrashlytics(enabled: Boolean) = session.withActions { it.setCrashlytics?.invoke(enabled) }

    fun submit() = session.withActions { it.submit?.invoke() }

    fun close() = session.close()
}

internal data class MasterPasswordActions(
    val setPassword: ((String) -> Unit)? = null,
    val setBiometric: ((Boolean) -> Unit)? = null,
    val setCrashlytics: ((Boolean) -> Unit)? = null,
    val submit: (() -> Unit)? = null,
)
