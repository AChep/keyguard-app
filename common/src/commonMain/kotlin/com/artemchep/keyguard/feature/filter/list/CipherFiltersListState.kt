package com.artemchep.keyguard.feature.filter.list

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import arrow.core.Either
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.ui.Selection
import kotlinx.coroutines.flow.StateFlow

data class CipherFiltersListState(
    val filter: StateFlow<Filter>,
    val selection: StateFlow<Selection?>,
    val content: Loadable<Either<Throwable, Content>>,
) {
    @Immutable
    data class Filter(
        val revision: Int,
        val query: TextFieldModel2,
    ) {
        companion object
    }

    @Immutable
    data class Content(
        val revision: Int,
        val items: List<Item>,
    ) {
        companion object
    }

    @Immutable
    data class Item(
        val key: String,
        val icon: VaultItemIcon,
        val shapeState: Int = ShapeState.ALL,
        val accentLight: Color,
        val accentDark: Color,
        val name: AnnotatedString,
        val data: DCipherFilter,
        val selectableState: StateFlow<SelectableItemState>,
        val onClick: () -> Unit,
    ) : GroupableShapeItem<Item> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}