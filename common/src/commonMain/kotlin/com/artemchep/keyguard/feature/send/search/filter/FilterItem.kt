package com.artemchep.keyguard.feature.send.search.filter

import androidx.compose.runtime.Composable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSendFilter
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel

@optics
sealed interface SendFilterItem : FilterItemModel {
    companion object

    val sectionId: String

    data class Section(
        override val sectionId: String,
        override val text: String,
        override val expandable: Boolean = true,
        override val expanded: Boolean = true,
        override val onClick: (() -> Unit)?,
    ) : SendFilterItem, FilterItemModel.Section {
        companion object;

        override val id: String = sectionId
    }

    data class Item(
        override val sectionId: String,
        val filterSectionId: String,
        val filters: Set<DSendFilter.Primitive>,
        override val checked: Boolean,
        override val fill: Boolean,
        override val indent: Int,
        override val leading: (@Composable () -> Unit)?,
        override val title: String,
        override val text: String?,
        override val textMaxLines: Int? = null,
        override val onClick: (() -> Unit)?,
        override val enabled: Boolean = onClick != null,
    ) : SendFilterItem, FilterItemModel.Item {
        companion object;

        override val id: String =
            sectionId + "|" + filterSectionId + "|" + filters.joinToString(separator = ",") { it.key }
    }
}
