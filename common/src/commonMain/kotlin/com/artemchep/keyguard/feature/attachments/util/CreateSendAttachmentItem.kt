package com.artemchep.keyguard.feature.attachments.util

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.coroutines.flow.MutableStateFlow

suspend fun RememberStateFlowScope.createAttachmentItem(
    attachment: DSend.File,
): AttachmentItem {
    val fileName = attachment.fileName
    val fileExt = attachment.fileName
    val fileSize = attachment
        .size
        ?.let(::humanReadableByteCountSI)

    val downloadStatusState = kotlin.run {
        val value = AttachmentItem.Status.None
        MutableStateFlow(value)
    }
    val actionsState = kotlin.run {
        val value = emptyList<ContextItem>()
        MutableStateFlow(value)
    }
    val selectableState = kotlin.run {
        val value = SelectableItemState(
            selecting = false,
            selected = false,
            can = false,
            onClick = null,
            onLongClick = null,
        )
        MutableStateFlow(value)
    }
    return AttachmentItem(
        key = "attachment.${attachment.id}",
        name = fileName,
        extension = fileExt,
        size = fileSize,
        statusState = downloadStatusState,
        actionsState = actionsState,
        selectableState = selectableState,
    )
}
