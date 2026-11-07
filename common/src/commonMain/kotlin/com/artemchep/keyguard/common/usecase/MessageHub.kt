package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.platform.WindowId

interface MessageHub {
    /**
     * Registers a consumer of the messages shown in the [windowId] window.
     *
     * A message that names its window reaches the consumers of that
     * window whether it is focused or not; a message without a window
     * reaches only the consumers that report [isFocused].
     */
    fun register(
        key: String,
        windowId: WindowId,
        isFocused: () -> Boolean = { true },
        onMessage: (ToastMessage) -> Unit,
    ): () -> Unit
}
