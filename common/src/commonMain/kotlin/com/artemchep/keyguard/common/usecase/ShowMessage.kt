package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage

interface ShowMessage {
    fun copy(
        value: ToastMessage,
        target: String? = null,
    )
}
