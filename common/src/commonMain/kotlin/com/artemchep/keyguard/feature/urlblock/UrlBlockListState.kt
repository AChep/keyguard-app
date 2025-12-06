package com.artemchep.keyguard.feature.urlblock

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import arrow.core.Either
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class UrlBlockListState(
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

    @Stable
    data class Item(
        val key: String,
        val title: String,
        val text: String,
        val uri: AnnotatedString,
        val mode: AnnotatedString,
        val shapeState: Int = ShapeState.ALL,
        val icon: VaultItemIcon,
        val accentLight: Color,
        val accentDark: Color,
        val active: Boolean,
        val dropdown: ImmutableList<ContextItem>,
        val selectableState: StateFlow<SelectableItemState>,
    ) : GroupableShapeItem<Item> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}