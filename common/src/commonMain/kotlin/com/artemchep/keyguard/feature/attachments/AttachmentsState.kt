package com.artemchep.keyguard.feature.attachments

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.ui.Selection

data class AttachmentsState(
    val filter: Filter,
    val stats: Stats,
    val selection: Selection?,
    val items: List<Item>,
) {
    data class Filter(
        val items: List<FilterItem> = emptyList(),
        val onClear: (() -> Unit)? = null,
        val onSave: (() -> Unit)? = null,
    )

    data class Stats(
        val totalAttachments: Int,
        val totalSize: String,
    )

    sealed interface Item {
        val key: String

        data class Section(
            override val key: String,
            val name: String,
        ) : Item

        data class Attachment(
            override val key: String,
            val item: AttachmentItem,
            val shapeState: Int = ShapeState.ALL,
        ) : Item, GroupableShapeItem<Attachment> {
            override fun withShape(shape: Int) = copy(shapeState = shape)
        }
    }
}

data class SelectableItemStateRaw(
    val selecting: Boolean,
    val selected: Boolean,
    val canSelect: Boolean = true,
)

@Immutable
data class SelectableItemState(
    val selecting: Boolean,
    val selected: Boolean,
    val can: Boolean = true,
    val onClick: (() -> Unit)?,
    val onLongClick: (() -> Unit)?,
)
