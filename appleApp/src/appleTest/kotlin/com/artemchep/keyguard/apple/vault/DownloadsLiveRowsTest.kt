package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.attachments.AttachmentsState
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsLiveRowsTest {
    @Test
    fun aRowStatusOrActionsChangeReEmitsTheList() = runTest {
        val status = MutableStateFlow<AttachmentItem.Status>(AttachmentItem.Status.Loading(downloaded = 1, total = 2))
        val actions = MutableStateFlow<List<ContextItem>>(emptyList())
        val state = Loadable.Ok(state(listOf(attachment("file", status, actions))))
        val frames = mutableListOf<Loadable<AttachmentsState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            MutableStateFlow(state).withLiveAttachmentRows().toList(frames)
        }
        assertEquals(1, frames.size)

        // The download finishes elsewhere: the list itself does not re-emit.
        status.value = AttachmentItem.Status.Downloaded(localUrl = "file:///tmp/file")
        assertEquals(2, frames.size)
        actions.value = listOf(FlatItemAction(title = TextHolder.Value("Open with")))
        assertEquals(3, frames.size)
        assertEquals(state, frames.last())
    }

    @Test
    fun aListWithoutAttachmentsStillEmits() = runTest {
        val frames = mutableListOf<Loadable<AttachmentsState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            MutableStateFlow<Loadable<AttachmentsState>>(Loadable.Ok(state(emptyList())))
                .withLiveAttachmentRows()
                .toList(frames)
        }
        assertEquals(1, frames.size)
    }

    private fun state(items: List<AttachmentsState.Item>) = AttachmentsState(
        filter = AttachmentsState.Filter(),
        stats = AttachmentsState.Stats(totalAttachments = items.size, totalSize = ""),
        selection = null,
        items = items,
    )

    private fun attachment(
        key: String,
        status: MutableStateFlow<AttachmentItem.Status>,
        actions: MutableStateFlow<List<ContextItem>>,
    ) = AttachmentsState.Item.Attachment(
        key = key,
        item = AttachmentItem(
            key = key,
            name = key,
            extension = null,
            size = null,
            statusState = status,
            actionsState = actions,
            selectableState = MutableStateFlow(
                SelectableItemState(selecting = false, selected = false, onClick = null, onLongClick = null),
            ),
        ),
    )
}
