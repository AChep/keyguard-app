package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.ui.graphics.vector.ImageVector
import arrow.optics.optics
import com.artemchep.keyguard.feature.home.vault.screen.ComparatorHolder
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.search.sort.model.SortItemModel

@optics
sealed interface SortItem : SortItemModel {
    companion object;

    data class Section(
        override val id: String,
        override val text: TextHolder? = null,
    ) : SortItem, SortItemModel.Section {
        companion object
    }

    data class Item(
        override val id: String,
        val config: ComparatorHolder,
        override val icon: ImageVector? = null,
        override val title: TextHolder,
        override val checked: Boolean,
        override val onClick: (() -> Unit)? = null,
    ) : SortItem, SortItemModel.Item
}
