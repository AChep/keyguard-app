package com.artemchep.keyguard.feature.home.vault.organizations

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.collections.immutable.ImmutableList

/**
 * @author Artem Chepurnyi
 */
@Immutable
data class OrganizationsState(
    val selection: Selection? = null,
    val content: Loadable<Content> = Loadable.Loading,
    val onAdd: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val items: ImmutableList<Item>,
    ) {
        @Immutable
        data class Item(
            val key: String,
            val title: String,
            val ciphers: Int,
            val selecting: Boolean,
            val selected: Boolean,
            val accentColors: AccentColors,
            val icon: ImageVector? = null,
            val actions: ImmutableList<ContextItem>,
            val onClick: (() -> Unit)?,
            val onLongClick: (() -> Unit)?,
        )
    }
}
