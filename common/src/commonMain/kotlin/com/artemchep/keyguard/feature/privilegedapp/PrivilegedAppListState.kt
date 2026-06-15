package com.artemchep.keyguard.feature.privilegedapp

import androidx.compose.runtime.Immutable
import arrow.core.Either
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class PrivilegedAppListState(
    val content: Loadable<Either<Throwable, Content>>,
) {
    @Immutable
    data class Content(
        val revision: Int,
        val items: ImmutableList<Item>,
        val selection: Selection?,
        val primaryAction: (() -> Unit)?,
    ) {
        companion object
    }

    @Immutable
    sealed interface Item {
        val key: String

        @Immutable
        data class Section(
            override val key: String,
            val name: String,
        ) : Item

        @Immutable
        data class Content(
            override val key: String,
            val shapeState: Int = ShapeState.ALL,
            val title: String,
            val cert: String,
            val dropdown: ImmutableList<ContextItem>,
            val selectableState: StateFlow<SelectableItemState>,
        ) : Item, GroupableShapeItem<Content> {
            override fun withShape(shape: Int) = copy(shapeState = shape)
        }
    }
}