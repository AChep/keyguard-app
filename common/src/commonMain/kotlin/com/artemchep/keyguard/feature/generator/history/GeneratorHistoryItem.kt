package com.artemchep.keyguard.feature.generator.history

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
@optics
sealed interface GeneratorHistoryItem {
    companion object

    val id: String

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
        val caps: Boolean = true,
    ) : GeneratorHistoryItem {
        companion object
    }

    @Immutable
    data class Value(
        override val id: String,
        val title: String,
        val text: String,
        val type: Type?,
        val createdDate: Instant,
        val shapeState: Int = ShapeState.ALL,
        /**
         * List of the callable actions appended
         * to the item.
         */
        val dropdown: PersistentList<ContextItem>,
        val selectableState: StateFlow<SelectableItemState>,
    ) : GeneratorHistoryItem, GroupableShapeItem<Value> {
        companion object;

        enum class Type {
            PASSWORD,
            USERNAME,
            EMAIL,
            EMAIL_RELAY,
            SSH_KEY,
        }

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
