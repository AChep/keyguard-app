package com.artemchep.keyguard.feature.changepassword

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

data class ChangePasswordState(
    val password: Password,
    val isLoading: Boolean = false,
    val onConfirm: (() -> Unit)? = null,
) {
    @Immutable
    @optics
    data class Password(
        val current: TextFieldModel,
        val new: TextFieldModel,
    ) {
        companion object
    }
}
