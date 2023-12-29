package com.artemchep.keyguard.feature.send.search

import androidx.compose.ui.graphics.vector.ImageVector
import arrow.optics.optics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.search.sort.model.SortItemModel
import com.artemchep.keyguard.feature.send.ComparatorHolder

@optics
sealed interface SendSortItem : SortItemModel {
    companion object;

    data class Section(
        override val id: String,
        override val text: TextHolder? = null,
    ) : SendSortItem, SortItemModel.Section {
        companion object
    }

    data class Item(
        override val id: String,
        val config: ComparatorHolder,
        override val icon: ImageVector? = null,
        override val title: TextHolder,
        override val checked: Boolean,
        override val onClick: (() -> Unit)? = null,
    ) : SendSortItem, SortItemModel.Item
}
