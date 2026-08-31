package com.artemchep.keyguard.feature.gpgkey.replacement

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

@Immutable
data class GpgUserIdReplacementState(
    val value: TextFieldModel = TextFieldModel.empty,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
)
