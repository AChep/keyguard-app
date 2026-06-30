package com.artemchep.keyguard.feature.feedback

import com.artemchep.keyguard.feature.auth.common.TextFieldModel

data class FeedbackState(
    val message: TextFieldModel,
    val onClear: (() -> Unit)? = null,
    val onSendClick: (() -> Unit)? = null,
)
