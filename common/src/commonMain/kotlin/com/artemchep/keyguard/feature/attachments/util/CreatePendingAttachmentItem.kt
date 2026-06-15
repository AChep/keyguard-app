package com.artemchep.keyguard.feature.attachments.util

import arrow.core.partially1
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.selection.SelectionHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

suspend fun RememberStateFlowScope.createPendingAttachmentItem(
    attachment: DSecret.Attachment.Local,
    selectionHandle: SelectionHandle,
    sharingScope: CoroutineScope,
): AttachmentItem {
    val fileName = attachment.fileName()
    val fileExt = attachment.fileName()
    val fileSize = attachment
        .fileSize()
        ?.let(::humanReadableByteCountSI)

    val statusState = MutableStateFlow<AttachmentItem.Status>(
        value = AttachmentItem.Status.PendingUpload,
    )
    val actionsState = MutableStateFlow(
        value = emptyList<ContextItem>(),
    )
    val selectableState = selectionHandle
        .idsFlow
        .map { selectedIds ->
            SelectableItemStateRaw(
                selecting = selectedIds.isNotEmpty(),
                selected = attachment.id in selectedIds,
            )
        }
        .distinctUntilChanged()
        .map { raw ->
            val onClick = if (raw.selecting) {
                selectionHandle::toggleSelection.partially1(
                    attachment.id,
                )
            } else {
                null
            }
            val onLongClick = if (raw.selecting) {
                null
            } else {
                selectionHandle::toggleSelection.partially1(
                    attachment.id,
                )
            }
            SelectableItemState(
                selecting = raw.selecting,
                selected = raw.selected,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
        .stateIn(sharingScope)
    return AttachmentItem(
        key = "attachment.${attachment.id}",
        name = fileName,
        extension = fileExt,
        size = fileSize,
        statusState = statusState,
        actionsState = actionsState,
        selectableState = selectableState,
    )
}
