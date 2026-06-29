package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.runtime.Composable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel

@optics
sealed interface FilterItem : FilterItemModel {
    companion object

    val sectionId: String

    data class Section(
        override val sectionId: String,
        override val text: String,
        override val expandable: Boolean = true,
        override val expanded: Boolean = true,
        override val layout: FilterItemModel.Section.Layout = FilterItemModel.Section.Layout.Flow,
        override val onClick: (() -> Unit)?,
    ) : FilterItem, FilterItemModel.Section {
        companion object;

        override val id: String = sectionId
    }

    sealed interface Item : FilterItem {
        val filterSectionId: String
        val filter: Filter?

        /**
         * Unique identifier of the set of
         * filters.
         */
        val filterId: String get() = filter?.id.orEmpty()

        val checked: Boolean

        val enabled: Boolean

        sealed interface Filter {
            val id: String

            data class Toggle(
                val filters: Set<DFilter.Primitive>,
                override val id: String = filters
                    .joinToString(separator = ",") { it.key },
            ) : Filter

            data class Apply(
                val filters: Map<String, Set<DFilter.Primitive>>,
                override val id: String,
            ) : Filter
        }
    }

    data class ChipItem(
        override val sectionId: String,
        override val filterSectionId: String,
        override val filter: Item.Filter,
        /**
         * Unique identifier of the set of
         * filters.
         */
        override val filterId: String = filter.id,
        override val checked: Boolean,
        override val leading: (@Composable () -> Unit)?,
        override val title: String,
        override val text: String?,
        override val textMaxLines: Int? = null,
        override val onClick: (() -> Unit)?,
        override val enabled: Boolean = onClick != null,
    ) : FilterItem, FilterItemModel.ChipItem, Item {
        companion object;

        override val id: String = "$sectionId|$filterSectionId|$filterId"
    }

    data class ListItem(
        override val sectionId: String,
        override val filterSectionId: String,
        override val filter: Item.Filter?,
        /**
         * Unique identifier of the set of
         * filters.
         */
        override val filterId: String = filter?.id.orEmpty(),
        override val nodeId: String,
        override val parentNodeId: String?,
        override val checked: Boolean,
        override val depth: Int,
        override val expandable: Boolean,
        override val leading: (@Composable () -> Unit)?,
        override val title: String,
        override val text: String?,
        override val textMaxLines: Int? = null,
        override val onClick: (() -> Unit)?,
        override val enabled: Boolean = onClick != null,
    ) : FilterItem, FilterItemModel.ListItem, Item {
        companion object;

        override val id: String = "$sectionId|$filterSectionId|$filterId|$nodeId"
    }
}
