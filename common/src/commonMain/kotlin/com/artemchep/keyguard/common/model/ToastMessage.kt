package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.WindowId
import kotlin.uuid.Uuid

data class ToastMessage(
    val id: String = Uuid.random().toString(),
    val type: Type? = null,
    val title: String,
    val text: String? = null,
    val action: Action? = null,
    /**
     * The ID of the window that should display the
     * message. See: [com.artemchep.keyguard.platform.LocalWindowId].
     */
    val windowId: WindowId? = null,
) {
    enum class Type {
        INFO,
        ERROR,
        SUCCESS,
    }

    data class Action(
        val title: String,
        val onClick: () -> Unit,
    )
}
