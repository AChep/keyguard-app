package com.artemchep.keyguard.feature.home.vault.collections

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList

/**
 * @author Artem Chepurnyi
 */
@Immutable
data class CollectionsState(
    val selection: Selection? = null,
    val content: Loadable<Content> = Loadable.Loading,
    val onAdd: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val title: Title,
        val items: ImmutableList<Item>,
    ) {
        @Immutable
        data class Title(
            val title: String? = null,
            val subtitle: String? = null,
        )

        @Immutable
        sealed interface Item {
            val key: String

            @Immutable
            data class Section(
                override val key: String,
                val text: String? = null,
            ) : Item

            @Immutable
            data class Collection(
                override val key: String,
                val title: String,
                val shapeState: Int = ShapeState.ALL,
                val ciphers: Int,
                val selecting: Boolean,
                val selected: Boolean,
                val organization: DOrganization?,
                val icon: ImageVector? = null,
                val actions: ImmutableList<ContextItem>,
                val onClick: (() -> Unit)?,
                val onLongClick: (() -> Unit)?,
            ) : Item
        }
    }
}
