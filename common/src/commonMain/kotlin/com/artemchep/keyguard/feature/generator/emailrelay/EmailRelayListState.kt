package com.artemchep.keyguard.feature.generator.emailrelay

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class EmailRelayListState(
    val content: Loadable<Either<Throwable, Content>>,
) {
    @Immutable
    data class Content(
        val revision: Int,
        val items: ImmutableList<Item>,
        val selection: Selection?,
        val primaryActions: ImmutableList<ContextItem>,
    ) {
        companion object
    }

    @Stable
    data class Item(
        val key: String,
        val title: String,
        val service: String,
        val icon: VaultItemIcon,
        val accentLight: Color,
        val accentDark: Color,
        val shapeState: Int = ShapeState.ALL,
        val dropdown: ImmutableList<ContextItem>,
        val selectableState: StateFlow<SelectableItemState>,
    )
}