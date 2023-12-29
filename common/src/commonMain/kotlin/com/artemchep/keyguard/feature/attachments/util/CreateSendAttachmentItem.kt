package com.artemchep.keyguard.feature.attachments.util

import arrow.core.partially1
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.FooStatus
import com.artemchep.keyguard.feature.attachments.LaunchViewCipherData
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.attachments.foo
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.selection.SelectionHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

suspend fun RememberStateFlowScope.createAttachmentItem(
    attachment: DSend.File,
    tag: DownloadInfoEntity2.AttachmentDownloadTag,
    selectionHandle: SelectionHandle,
    sharingScope: CoroutineScope,
    launchViewCipherData: LaunchViewCipherData?,
    downloadManager: DownloadManager,
    downloadIo: IO<Unit>,
    removeIo: IO<Unit>,
): AttachmentItem {
    val fileName = attachment.fileName
    val fileExt = attachment.fileName
    val fileSize = attachment
        .size
        ?.let(::humanReadableByteCountSI)

    val downloadStatusState = downloadManager
        .statusByTag(tag)
        .map { downloadStatus ->
            AttachmentItem.Status.of(
                downloadStatus = downloadStatus,
            )
        }
        .persistingStateIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(1000L),
        )
    val actionsState = downloadStatusState
        .map { attachmentStatus ->
            FooStatus.of(
                attachmentStatus = attachmentStatus,
            )
        }
        .distinctUntilChanged()
        .map { actionsStatus ->
            val actions = foo(
                translatorScope = this,
                fileName = fileName,
                launchViewCipherData = launchViewCipherData,
                status = actionsStatus,
                downloadIo = downloadIo,
                removeIo = removeIo,
                navigate = ::navigate,
            )
            actions
        }
        .persistingStateIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(1000L),
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
                // lambda
                selectionHandle::toggleSelection.partially1(
                    attachment.id,
                )
            } else {
                null
            }
            val onLongClick = if (raw.selecting) {
                null
            } else {
                // lambda
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
        statusState = downloadStatusState,
        actionsState = actionsState,
        selectableState = selectableState,
    )
}
