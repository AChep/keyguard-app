package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService

/**
 * @author Artem Chepurnyi
 */
class CopyText(
    private val clipboardService: ClipboardService,
    private val onMessage: (ToastMessage) -> Unit,
) {
    fun copy(
        text: String,
        hidden: Boolean,
    ) {
        clipboardService.setPrimaryClip(text, concealed = hidden)
        if (!clipboardService.hasCopyNotification()) {
            val message = ToastMessage(
                title = "Copied a value",
                text = text.takeUnless { hidden },
            )
            onMessage(message)
        }
    }
}
