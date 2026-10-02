package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** One password editor's fields and completion. Methods and callbacks are main-confined. */
class ChangePasswordSession internal constructor(
    private val subscribe: (
        publish: (ChangePasswordSnapshot, ChangePasswordActions) -> Unit,
        complete: () -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<ChangePasswordSnapshot, ChangePasswordActions>()

    fun observe(onChange: (ChangePasswordSnapshot) -> Unit, onClose: () -> Unit): KeyguardCancellable =
        session.observe(onChange, onClose, subscribe)

    fun setCurrentPassword(text: String) = session.withActions { it.setCurrentPassword?.invoke(text) }

    fun setNewPassword(text: String) = session.withActions { it.setNewPassword?.invoke(text) }

    fun setBiometric(enabled: Boolean) = session.withActions { it.setBiometric?.invoke(enabled) }

    fun submit() = session.withActions { it.submit?.invoke() }

    fun close() = session.close()
}

internal data class ChangePasswordActions(
    val setCurrentPassword: ((String) -> Unit)? = null,
    val setNewPassword: ((String) -> Unit)? = null,
    val setBiometric: ((Boolean) -> Unit)? = null,
    val submit: (() -> Unit)? = null,
)
