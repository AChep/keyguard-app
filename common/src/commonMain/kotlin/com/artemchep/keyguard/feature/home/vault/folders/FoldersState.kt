package com.artemchep.keyguard.feature.home.vault.folders

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList

/**
 * @author Artem Chepurnyi
 */
@Immutable
data class FoldersState(
    val selection: Selection? = null,
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
            data class Folder(
                override val key: String,
                val title: String,
                val text: String? = null,
                val ciphers: Int,
                val selecting: Boolean,
                val selected: Boolean,
                val synced: Boolean,
                val failed: Boolean,
                val icon: ImageVector? = null,
                val actions: ImmutableList<ContextItem>,
                val onClick: (() -> Unit)?,
                val onLongClick: (() -> Unit)?,
            ) : Item
        }
    }
}
