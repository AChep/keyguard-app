package com.artemchep.keyguard.feature.feedback

import com.artemchep.keyguard.feature.auth.common.TextFieldModel2

data class FeedbackState(
    val message: TextFieldModel2,
    val onClear: (() -> Unit)? = null,
    val onSendClick: (() -> Unit)? = null,
)
