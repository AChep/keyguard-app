package com.artemchep.keyguard.feature.passwordmemory

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

@Immutable
data class PasswordMemoryState(
    val password: TextFieldModel = TextFieldModel.empty,
    val onVerify: (() -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
)
