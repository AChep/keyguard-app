package com.artemchep.keyguard.feature.apppicker.model

import androidx.compose.ui.graphics.vector.ImageVector
import arrow.optics.optics
import com.artemchep.keyguard.feature.apppicker.AppPickerComparatorHolder
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.search.sort.model.SortItemModel

@optics
sealed interface AppPickerSortItem : SortItemModel {
    companion object;

    data class Section(
        override val id: String,
        override val text: TextHolder? = null,
    ) : AppPickerSortItem, SortItemModel.Section {
        companion object
    }

    data class Item(
        override val id: String,
        val config: AppPickerComparatorHolder,
        override val icon: ImageVector? = null,
        override val title: TextHolder,
        override val checked: Boolean,
        override val onClick: (() -> Unit)? = null,
    ) : AppPickerSortItem, SortItemModel.Item
}
