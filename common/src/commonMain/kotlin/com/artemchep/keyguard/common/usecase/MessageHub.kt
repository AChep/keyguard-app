package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.platform.WindowId

interface MessageHub {
    fun register(
        key: String,
        windowId: WindowId,
        onMessage: (ToastMessage) -> Unit,
    ): () -> Unit
}
