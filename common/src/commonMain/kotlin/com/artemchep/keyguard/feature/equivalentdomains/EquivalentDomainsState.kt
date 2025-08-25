package com.artemchep.keyguard.feature.equivalentdomains

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import kotlinx.collections.immutable.ImmutableList

/**
 * @author Artem Chepurnyi
 */
@Immutable
data class EquivalentDomainsState(
    val content: Loadable<Content> = Loadable.Loading,
    val onAdd: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val items: ImmutableList<Item>,
    ) {

        @Immutable
        sealed interface Item {
            val key: String

            @Immutable
            data class Section(
                override val key: String,
                val text: String? = null,
            ) : Item

            @Immutable
            data class Content(
                override val key: String,
                val title: String,
                val excluded: Boolean,
                val global: Boolean,
                val shapeState: Int = ShapeState.ALL,
                val onClick: (() -> Unit)?,
            ) : Item, GroupableShapeItem<Content> {
                override fun withShape(shape: Int) = copy(shapeState = shape)
            }
        }
    }
}
