package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage

interface MessageHub {
    fun register(key: String, onMessage: (ToastMessage) -> Unit): () -> Unit
}
